package app.kiln.llm

import app.kiln.core.arrOf
import app.kiln.core.compact
import app.kiln.core.obj
import app.kiln.core.parseJson
import app.kiln.core.str
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.RateLimitException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration

/**
 * `anthropic-messages` through the official Anthropic Java SDK (beta Messages
 * endpoint, for context editing, compaction, task budgets and fallbacks).
 *
 * The request is built as wire JSON from the canonical transcript and handed
 * to the SDK as a typed body (`MessageCreateParams.Body` via the SDK's own
 * Jackson mapper); the accumulated response is serialized back to wire JSON.
 * So the transcript keeps every block exactly as Claude sent it — thinking
 * signatures and compaction blocks included — which append-only replay needs.
 */
class AnthropicAdapter(
    private val profile: Profile,
    apiKey: String?,
) : Adapter {
    private val caps = profile.caps
    private val origin get() = profile.id
    private val mapper = jsonMapper()

    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apply {
        baseUrl(Http.rootBase(profile.baseUrl))
        when (profile.auth) {
            AuthStyle.X_API_KEY -> apiKey(apiKey ?: "")
            AuthStyle.BEARER -> authToken(apiKey ?: "")
            AuthStyle.NONE -> apiKey("none")
        }
        profile.headers.forEach { (k, v) -> putHeader(k, v) }
        profile.pinnedCertSha256?.let { val t = Http.pinnedTls(it); sslSocketFactory(t.factory); trustManager(t.trust); hostnameVerifier(t.hostnames) }
        maxRetries(0)   // the agent loop owns retries and fallbacks; two layers multiplied attempts
        timeout(Duration.ofMinutes(15))
    }.build()

    override suspend fun stream(req: ModelRequest, onEvent: (ModelEvent) -> Unit): ModelTurn {
        val open = java.util.concurrent.atomic.AtomicReference<AutoCloseable?>()
        return abandonable(onAbandon = { open.get()?.close() }) { abandoned -> streamBlocking(req, abandoned, open) { if (!abandoned()) onEvent(it) } }
    }

    private fun streamBlocking(req: ModelRequest, abandoned: () -> Boolean, open: java.util.concurrent.atomic.AtomicReference<AutoCloseable?>,
                               onEvent: (ModelEvent) -> Unit): ModelTurn {
        Http.checkUrl(profile.baseUrl)
        val (body, betas) = buildBody(req)
        val params = MessageCreateParams.builder()
            .body(mapper.readValue(body.compact(), MessageCreateParams.Body::class.java))
            .apply { betas.forEach { addBeta(it) } }
            .build()
        val acc = BetaMessageAccumulator.create()
        // Anthropic-format proxies in front of local reasoning models can still send
        // inline <think>…</think> as plain text; route it to thinking like chat/completions does.
        val tags = ThinkTags()
        try {
            client.beta().messages().createStreaming(params).use { stream ->
                // Stop closes the stream (see abandonable), which unblocks the read; each event re-checks.
                open.set(stream)
                if (abandoned()) throw kotlinx.coroutines.CancellationException("stopped")
                stream.stream().forEach { ev ->
                    if (abandoned()) throw kotlinx.coroutines.CancellationException("stopped")
                    acc.accumulate(ev)
                    emit(parseJson(mapper.writeValueAsString(ev)) as JsonObject, tags, onEvent)
                }
            }
            tags.flush { t, think -> onEvent(if (think) ModelEvent.Thinking(t) else ModelEvent.Text(t)) }
        } catch (e: RateLimitException) {
            throw ProviderException("rate limited: ${e.message}", retryable = true, e)
        } catch (e: InternalServerException) {
            throw ProviderException("server error: ${e.message}", retryable = true, e)
        } catch (e: AnthropicServiceException) {
            // An error inside a stream (overloaded_error, api_error) arrives under the stream's 200: judge it by its text.
            val transient = e.statusCode() == 529 || e.statusCode() >= 500 ||
                (e.statusCode() in 200..299 && Regex("overload|api_error|rate.?limit|capacity|timeout|unavailable", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty()))
            throw ProviderException("${e.statusCode()}: ${e.message}", retryable = transient, e)
        }
        val msg = parseJson(mapper.writeValueAsString(acc.message())) as JsonObject
        val content = splitThinkTags(msg["content"] as? JsonArray ?: JsonArray(emptyList()))
        val u = msg["usage"] as? JsonObject
        val usage = Usage(
            input = (u?.get("input_tokens") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0,
            output = (u?.get("output_tokens") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0,
            cacheRead = (u?.get("cache_read_input_tokens") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0,
            cacheWrite = (u?.get("cache_creation_input_tokens") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0,
        )
        val stop = when (msg.str("stop_reason")) {
            "tool_use" -> Stop.TOOL_USE; "end_turn", "stop_sequence" -> Stop.END_TURN
            "max_tokens" -> Stop.MAX_TOKENS; "refusal" -> Stop.REFUSAL; "pause_turn" -> Stop.PAUSE_TURN
            else -> Stop.OTHER
        }
        val refusal = (msg["stop_details"] as? JsonObject)?.let { d -> listOfNotNull(d.str("category"), d.str("explanation")).joinToString(": ") }
        return ModelTurn(content, stop, usage, refusal = refusal)
    }

    private fun emit(ev: JsonObject, tags: ThinkTags, onEvent: (ModelEvent) -> Unit) {
        when (ev.str("type")) {
            "content_block_start" -> {
                val b = ev["content_block"] as? JsonObject ?: return
                if (b.str("type") == "tool_use") onEvent(ModelEvent.ToolStart(b.str("id") ?: "", b.str("name") ?: ""))
                if (b.str("type") == "server_tool_use") onEvent(ModelEvent.Status("server tool: ${b.str("name")}"))
            }
            "content_block_delta" -> {
                val d = ev["delta"] as? JsonObject ?: return
                when (d.str("type")) {
                    "text_delta" -> tags.feed(d.str("text") ?: "") { t, think -> onEvent(if (think) ModelEvent.Thinking(t) else ModelEvent.Text(t)) }
                    "thinking_delta" -> onEvent(ModelEvent.Thinking(d.str("thinking") ?: ""))
                    "input_json_delta" -> onEvent(ModelEvent.ToolArgs("", d.str("partial_json") ?: ""))
                }
            }
        }
    }

    /** Canonical transcript + request → wire body and the beta flags it needs. */
    fun buildBody(req: ModelRequest): Pair<JsonObject, List<String>> {
        val betas = mutableListOf<String>()
        val tools = req.tools.map { t ->
            buildJsonObject {
                put("name", t.name); put("description", t.description); put("input_schema", t.schema)
                if (t.strict && caps.strictTools) put("strict", true)
                put("eager_input_streaming", true)
            }
        } + if (caps.serverWebSearch) req.serverTools else emptyList()

        val edits = mutableListOf<JsonObject>()
        if (caps.contextEditing) {
            edits += obj("type" to "clear_tool_uses_20250919",
                "trigger" to obj("type" to "input_tokens", "value" to 120_000),
                "keep" to obj("type" to "tool_uses", "value" to 8))
            betas += "context-management-2025-06-27"
        }
        if (caps.compaction) { edits += obj("type" to "compact_20260112"); betas += "compact-2026-01-12" }

        val output = buildJsonObject {
            if (caps.effort && req.effort != null) put("effort", req.effort)
            if (caps.taskBudget && req.taskBudgetTokens != null && req.taskBudgetTokens >= 20_000) {
                put("task_budget", obj("type" to "tokens", "total" to req.taskBudgetTokens)); betas += "task-budgets-2026-03-13"
            }
        }
        if (caps.fallbacks) betas += "server-side-fallback-2026-07-01"

        val body = buildJsonObject {
            put("model", req.model)
            put("max_tokens", req.maxTokens.coerceAtMost(caps.maxOutput))
            put("stream", true)
            put("system", arrOf(listOf(obj("type" to "text", "text" to req.system,
                "cache_control" to if (caps.caching) obj("type" to "ephemeral") else null))))
            put("messages", JsonArray(req.messages.mapNotNull { toWire(it, req.model) }))
            if (tools.isNotEmpty()) put("tools", JsonArray(tools))
            if (caps.thinking) put("thinking", obj("type" to "adaptive", "display" to "summarized"))
            if (output.isNotEmpty()) put("output_config", output)
            if (caps.caching) put("cache_control", obj("type" to "ephemeral"))
            if (edits.isNotEmpty()) put("context_management", obj("edits" to edits))
            if (caps.fallbacks) put("fallbacks", "default")
        }
        return body to betas.distinct()
    }

    /** Opaque blocks (thinking, compaction, server tools) go back only to the turn's own origin. */
    private fun toWire(m: Msg, model: String): JsonObject? {
        val same = m.origin == null || m.origin == "$origin|$model"
        val opaque = setOf("thinking", "redacted_thinking", "compaction", "server_tool_use", "web_search_tool_result")
        val blocks = m.content.filter { b ->
            val t = (b as? JsonObject)?.str("type")
            same || t !in opaque
        }.map { b -> (b as JsonObject).let { if (it.str("type") == "thinking" && it["kiln_display_only"] != null) null else it } }
            .filterNotNull()
        if (blocks.isEmpty()) return null
        return when (m.role) {
            "system" -> if (caps.midSystem) obj("role" to "system", "content" to blocks.joinToString("\n") { it.str("text") ?: "" })
                        else obj("role" to "user", "content" to arrOf(listOf(obj("type" to "text",
                            "text" to "<system-reminder>\n" + blocks.joinToString("\n") { it.str("text") ?: "" } + "\n</system-reminder>"))))
            else -> obj("role" to m.role, "content" to JsonArray(blocks))
        }
    }

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        runCatching { client.models().list().autoPager().map { it.id() }.toList() }.getOrDefault(emptyList())
    }
}
