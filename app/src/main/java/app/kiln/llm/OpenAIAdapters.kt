package app.kiln.llm

import app.kiln.core.arrOf
import app.kiln.core.compact
import app.kiln.core.obj
import app.kiln.core.parseJson
import app.kiln.core.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Shared plumbing for the OpenAI-shaped protocols. */
abstract class OpenAIBase(protected val profile: Profile, private val apiKey: String?) : Adapter {
    protected val caps = profile.caps
    protected val http: OkHttpClient = Http.client(profile)
    protected val base = Http.v1Base(profile.baseUrl)

    protected fun request(path: String, body: JsonObject?): Request {
        Http.checkUrl(base)
        val b = Request.Builder().url("$base$path")
        when (profile.auth) {
            AuthStyle.BEARER -> apiKey?.let { b.header("Authorization", "Bearer $it") }
            AuthStyle.X_API_KEY -> apiKey?.let { b.header("x-api-key", it) }
            AuthStyle.NONE -> {}
        }
        profile.headers.forEach { (k, v) -> b.header(k, v) }
        if (body != null) b.post(body.compact().toRequestBody("application/json".toMediaType()))
        return b.build()
    }

    protected fun check(r: Response) {
        if (r.isSuccessful) return
        val text = r.body.string().take(600)
        throw ProviderException("HTTP ${r.code}: $text", retryable = r.code == 429 || r.code >= 500)
    }

    /** Server-sent events: yields (event, data) pairs until [DONE] or EOF. */
    protected suspend fun sse(r: Response, each: (String?, String) -> Unit) {
        val src = r.body.source()
        var event: String? = null
        val data = StringBuilder()
        while (true) {
            currentCoroutineContext().ensureActive()
            val line = src.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty()) {
                        val d = data.toString(); data.clear()
                        if (d == "[DONE]") return
                        each(event, d)
                    }
                    event = null
                }
                line.startsWith(":") -> {}
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> { if (data.isNotEmpty()) data.append('\n'); data.append(line.removePrefix("data:").trimStart()) }
            }
        }
        if (data.isNotEmpty() && data.toString() != "[DONE]") each(event, data.toString())
    }

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(request("/models", null)).execute().use { r ->
                check(r)
                ((parseJson(r.body.string()) as JsonObject)["data"] as JsonArray).mapNotNull { (it as JsonObject).str("id") }
            }
        }.getOrDefault(emptyList())
    }

    protected fun textOf(blocks: JsonArray): String =
        blocks.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }.joinToString("\n")

    protected fun imageUrl(b: JsonObject): String? {
        val src = b["source"] as? JsonObject ?: return null
        return when (src.str("type")) {
            "base64" -> "data:${src.str("media_type")};base64,${src.str("data")}"
            "url" -> src.str("url")
            else -> null
        }
    }

    protected fun toolResultText(b: JsonObject): Pair<String, List<String>> {
        val c = b["content"]
        val images = mutableListOf<String>()
        val text = when (c) {
            is JsonPrimitive -> c.content
            is JsonArray -> c.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                when (o.str("type")) { "text" -> o.str("text"); "image" -> { imageUrl(o)?.let { images += it }; "[image attached below]" }; else -> null }
            }.joinToString("\n")
            else -> ""
        }
        val err = if ((b["is_error"] as? JsonPrimitive)?.content == "true") "ERROR: " else ""
        return err + text to images
    }

    protected fun systemReminder(m: Msg) = "<system-reminder>\n${textOf(m.content)}\n</system-reminder>"

    protected fun stopFrom(reason: String?) = when (reason) {
        "tool_calls", "function_call" -> Stop.TOOL_USE
        "stop", "end_turn", "completed" -> Stop.END_TURN
        "length", "max_output_tokens", "max_tokens" -> Stop.MAX_TOKENS
        "content_filter" -> Stop.REFUSAL
        else -> Stop.OTHER
    }
}

/** `openai-chat`: POST /v1/chat/completions (OpenRouter, llama.cpp, Ollama, LM Studio, vLLM, proxies). */
class OpenAIChatAdapter(profile: Profile, apiKey: String?) : OpenAIBase(profile, apiKey) {
    private val isOpenRouter = "openrouter.ai" in profile.baseUrl

    fun buildBody(req: ModelRequest): JsonObject {
        val msgs = mutableListOf<JsonElement>(obj("role" to "system", "content" to req.system))
        for (m in req.messages) msgs += convert(m)
        return buildJsonObject {
            put("model", req.model)
            put("messages", JsonArray(msgs))
            put("stream", true)
            put("stream_options", obj("include_usage" to true))
            put("max_tokens", req.maxTokens.coerceAtMost(caps.maxOutput))
            if (req.tools.isNotEmpty()) {
                put("tools", JsonArray(req.tools.map { t ->
                    obj("type" to "function", "function" to obj("name" to t.name, "description" to t.description,
                        "parameters" to t.schema, "strict" to if (t.strict && caps.strictTools) true else null))
                }))
                if (!caps.parallelTools) put("parallel_tool_calls", false)
            }
            if (caps.effort && req.effort != null) {
                if (isOpenRouter) put("reasoning", obj("effort" to req.effort.let { if (it == "xhigh" || it == "max") "high" else it }))
                else put("reasoning_effort", req.effort.let { if (it == "xhigh" || it == "max") "high" else it })
            }
        }
    }

    private fun convert(m: Msg): List<JsonElement> {
        if (m.role == "system") return listOf(obj("role" to "user", "content" to systemReminder(m)))
        if (m.role == "assistant") {
            val text = textOf(m.content)
            val calls = m.content.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "tool_use" } }.map { b ->
                obj("id" to b.str("id"), "type" to "function",
                    "function" to obj("name" to b.str("name"), "arguments" to (b["input"] ?: obj()).compact()))
            }
            return listOf(obj("role" to "assistant", "content" to text.ifBlank { null },
                "tool_calls" to if (calls.isNotEmpty()) calls else null))
        }
        // user: tool results become role:tool messages; images in them are lifted into a user message.
        val out = mutableListOf<JsonElement>()
        val lifted = mutableListOf<String>()
        val parts = mutableListOf<JsonElement>()
        for (e in m.content) {
            val b = e as? JsonObject ?: continue
            when (b.str("type")) {
                "tool_result" -> { val (t, imgs) = toolResultText(b); out += obj("role" to "tool", "tool_call_id" to b.str("tool_use_id"), "content" to t); lifted += imgs }
                "text" -> parts += obj("type" to "text", "text" to b.str("text"))
                "image" -> imageUrl(b)?.let { parts += obj("type" to "image_url", "image_url" to obj("url" to it)) }
            }
        }
        if (lifted.isNotEmpty() && caps.vision)
            parts += lifted.map { obj("type" to "image_url", "image_url" to obj("url" to it)) }
        if (parts.isNotEmpty()) out += obj("role" to "user", "content" to JsonArray(parts))
        return out
    }

    override suspend fun stream(req: ModelRequest, onEvent: (ModelEvent) -> Unit): ModelTurn = withContext(Dispatchers.IO) {
        val text = StringBuilder(); val thinking = StringBuilder()
        data class Call(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())
        val calls = sortedMapOf<Int, Call>()
        var finish: String? = null
        var usage = Usage()
        http.newCall(request("/chat/completions", buildBody(req))).execute().use { r ->
            check(r)
            sse(r) { _, data ->
                val j = parseJson(data) as? JsonObject ?: return@sse
                (j["error"] as? JsonObject)?.let { throw ProviderException(it.str("message") ?: data, retryable = false) }
                (j["usage"] as? JsonObject)?.let { u ->
                    val cached = ((u["prompt_tokens_details"] as? JsonObject)?.get("cached_tokens") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0
                    usage = Usage(
                        input = ((u["prompt_tokens"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0) - cached,
                        output = (u["completion_tokens"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0, cacheRead = cached)
                }
                val choice = (j["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@sse
                choice.str("finish_reason")?.let { finish = it }
                val d = choice["delta"] as? JsonObject ?: return@sse
                d.str("content")?.let { text.append(it); onEvent(ModelEvent.Text(it)) }
                (d.str("reasoning_content") ?: d.str("reasoning"))?.let { thinking.append(it); onEvent(ModelEvent.Thinking(it)) }
                (d["tool_calls"] as? JsonArray)?.forEach { tc ->
                    val o = tc as JsonObject
                    val idx = (o["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                    val c = calls.getOrPut(idx) { Call() }
                    o.str("id")?.let { c.id = it }
                    (o["function"] as? JsonObject)?.let { f ->
                        f.str("name")?.let { if (c.name.isEmpty()) { c.name = it; onEvent(ModelEvent.ToolStart(c.id, it)) } }
                        f.str("arguments")?.let { c.args.append(it); onEvent(ModelEvent.ToolArgs(c.id, it)) }
                    }
                }
            }
        }
        val blocks = mutableListOf<JsonElement>()
        if (thinking.isNotEmpty()) blocks += obj("type" to "thinking", "thinking" to thinking.toString(), "kiln_display_only" to true)
        if (text.isNotEmpty()) blocks += obj("type" to "text", "text" to text.toString())
        calls.values.forEachIndexed { i, c ->
            val input = runCatching { parseJson(c.args.toString().ifBlank { "{}" }) }.getOrElse { obj("_invalid_json" to c.args.toString()) }
            blocks += obj("type" to "tool_use", "id" to c.id.ifBlank { "call_$i" }, "name" to c.name, "input" to input)
        }
        val stop = if (calls.isNotEmpty()) Stop.TOOL_USE else stopFrom(finish)
        ModelTurn(JsonArray(blocks), stop, usage)
    }
}

/** `openai-responses`: POST /v1/responses (OpenAI; reasoning items kept encrypted for replay). */
class OpenAIResponsesAdapter(profile: Profile, apiKey: String?) : OpenAIBase(profile, apiKey) {

    fun buildBody(req: ModelRequest): JsonObject {
        val input = mutableListOf<JsonElement>()
        for (m in req.messages) input += convert(m, req.model)
        return buildJsonObject {
            put("model", req.model)
            put("instructions", req.system)
            put("input", JsonArray(input))
            put("stream", true)
            put("store", false)
            put("include", arrOf(listOf("reasoning.encrypted_content")))
            put("max_output_tokens", req.maxTokens.coerceAtMost(caps.maxOutput))
            if (req.tools.isNotEmpty()) put("tools", JsonArray(req.tools.map { t ->
                obj("type" to "function", "name" to t.name, "description" to t.description, "parameters" to t.schema,
                    "strict" to (t.strict && caps.strictTools))
            }))
            if (caps.effort && req.effort != null)
                put("reasoning", obj("effort" to req.effort.let { if (it == "max") "xhigh" else it }, "summary" to "auto"))
        }
    }

    private fun convert(m: Msg, model: String): List<JsonElement> {
        if (m.role == "system") return listOf(obj("role" to "developer", "content" to textOf(m.content)))
        if (m.role == "assistant") {
            // Same origin: replay the exact output items (reasoning + calls).
            val items = m.providerState?.get("items") as? JsonArray
            if (items != null && m.origin == "${profile.id}|$model") return items.toList()
            val out = mutableListOf<JsonElement>()
            textOf(m.content).takeIf { it.isNotBlank() }?.let {
                out += obj("role" to "assistant", "content" to arrOf(listOf(obj("type" to "output_text", "text" to it))))
            }
            m.content.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "tool_use" } }.forEach { b ->
                out += obj("type" to "function_call", "call_id" to b.str("id"), "name" to b.str("name"),
                    "arguments" to (b["input"] ?: obj()).compact())
            }
            return out
        }
        val out = mutableListOf<JsonElement>()
        val parts = mutableListOf<JsonElement>()
        val lifted = mutableListOf<String>()
        for (e in m.content) {
            val b = e as? JsonObject ?: continue
            when (b.str("type")) {
                "tool_result" -> { val (t, imgs) = toolResultText(b); out += obj("type" to "function_call_output", "call_id" to b.str("tool_use_id"), "output" to t); lifted += imgs }
                "text" -> parts += obj("type" to "input_text", "text" to b.str("text"))
                "image" -> imageUrl(b)?.let { parts += obj("type" to "input_image", "image_url" to it) }
            }
        }
        parts += lifted.map { obj("type" to "input_image", "image_url" to it) }
        if (parts.isNotEmpty()) out += obj("role" to "user", "content" to JsonArray(parts))
        return out
    }

    override suspend fun stream(req: ModelRequest, onEvent: (ModelEvent) -> Unit): ModelTurn = withContext(Dispatchers.IO) {
        var final: JsonObject? = null
        var incomplete: String? = null
        http.newCall(request("/responses", buildBody(req))).execute().use { r ->
            check(r)
            sse(r) { event, data ->
                val j = parseJson(data) as? JsonObject ?: return@sse
                when (event ?: j.str("type")) {
                    "response.output_text.delta" -> onEvent(ModelEvent.Text(j.str("delta") ?: ""))
                    "response.reasoning_summary_text.delta" -> onEvent(ModelEvent.Thinking(j.str("delta") ?: ""))
                    "response.output_item.added" -> (j["item"] as? JsonObject)?.takeIf { it.str("type") == "function_call" }
                        ?.let { onEvent(ModelEvent.ToolStart(it.str("call_id") ?: "", it.str("name") ?: "")) }
                    "response.function_call_arguments.delta" -> onEvent(ModelEvent.ToolArgs("", j.str("delta") ?: ""))
                    "response.completed" -> final = j["response"] as? JsonObject
                    "response.incomplete" -> { final = j["response"] as? JsonObject; incomplete = "max_output_tokens" }
                    "response.failed", "error" -> throw ProviderException(data.take(600), retryable = false)
                }
            }
        }
        val resp = final ?: throw ProviderException("stream ended without a response", retryable = true)
        val items = resp["output"] as? JsonArray ?: JsonArray(emptyList())
        val blocks = mutableListOf<JsonElement>()
        var calls = 0
        for (e in items) {
            val it = e as? JsonObject ?: continue
            when (it.str("type")) {
                "reasoning" -> {
                    val s = (it["summary"] as? JsonArray)?.mapNotNull { x -> (x as? JsonObject)?.str("text") }?.joinToString("\n")
                    if (!s.isNullOrBlank()) blocks += obj("type" to "thinking", "thinking" to s, "kiln_display_only" to true)
                }
                "message" -> (it["content"] as? JsonArray)?.forEach { c ->
                    (c as? JsonObject)?.takeIf { x -> x.str("type") == "output_text" }?.let { x -> blocks += obj("type" to "text", "text" to x.str("text")) }
                }
                "function_call" -> {
                    calls++
                    val input = runCatching { parseJson(it.str("arguments") ?: "{}") }.getOrElse { _ -> obj() }
                    blocks += obj("type" to "tool_use", "id" to it.str("call_id"), "name" to it.str("name"), "input" to input)
                }
            }
        }
        val u = resp["usage"] as? JsonObject
        val cached = ((u?.get("input_tokens_details") as? JsonObject)?.get("cached_tokens") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0
        val usage = Usage(
            input = ((u?.get("input_tokens") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0) - cached,
            output = (u?.get("output_tokens") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0, cacheRead = cached)
        val stop = if (calls > 0) Stop.TOOL_USE else if (incomplete != null) Stop.MAX_TOKENS else Stop.END_TURN
        ModelTurn(JsonArray(blocks), stop, usage, providerState = obj("items" to items))
    }
}
