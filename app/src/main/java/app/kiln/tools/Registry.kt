package app.kiln.tools

import app.kiln.core.Exec
import app.kiln.core.KJ
import app.kiln.core.compact
import app.kiln.core.obj
import app.kiln.core.parseJson
import app.kiln.core.str
import app.kiln.device.Warden
import app.kiln.llm.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
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
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class Policy { ALLOW, ASK, DENY }

// ---------------------------------------------------------------- command tools

/** A tool declared as JSON (SPEC §4.2): no Kotlin needed. */
@Serializable
data class CommandToolDef(
    val name: String,
    val description: String,
    val command: String,
    val runAs: String = "app",                               // app | broker
    val params: Map<String, JsonObject> = emptyMap(),        // name → JSON schema property
    val traits: List<String> = emptyList(),
    val maxOutputChars: Int = 16_000,
    val timeoutMs: Long = 60_000,
)

class CommandTool(private val def: CommandToolDef, private val warden: Warden, val trusted: Boolean = true) : Tool {
    override val name = def.name
    override val description = def.description + if (def.runAs == "broker") " (runs as shell through Warden)" else ""
    override val schema: JsonObject = obj("type" to "object", "properties" to JsonObject(def.params),
        "required" to JsonArray(def.params.keys.map { JsonPrimitive(it) }), "additionalProperties" to false)
    // A project's own tools.d can't declare itself read-only: it always asks first.
    override val traits = (if (trusted) def.traits.mapNotNull { runCatching { Trait.valueOf(it) }.getOrNull() }.toSet()
        else setOf(Trait.NEEDS_APPROVAL)) + if (def.runAs == "broker") setOf(Trait.NEEDS_BROKER) else emptySet()
    override val timeoutMs = def.timeoutMs

    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        // One pass: a value containing "{{other}}" must stay a literal inside its quotes, not pull the next
        // value in unquoted (review: shell injection through chained placeholders).
        val values = def.params.keys.associateWith { (input[it] as? JsonPrimitive)?.content ?: "" } +
            mapOf("app_package" to ctx.project.meta().`package`, "project_dir" to ctx.project.dir.path)
        val cmd = Regex("""\{\{([^{}]+)}}""").replace(def.command) { m -> values[m.groupValues[1]]?.let(::q) ?: m.value }
        val r = if (def.runAs == "broker") warden.exec(listOf("sh", "-c", cmd), timeoutMs = def.timeoutMs)
                else Exec.run(listOf("/system/bin/sh", "-c", cmd), cwd = ctx.project.dir, timeoutMs = def.timeoutMs)
        return ToolResult(ctx.spill("exit ${r.code}\n${r.all}", def.maxOutputChars), isError = !r.ok)
    }

    /** Single-quote for sh: parameters can never break out of their argument. */
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    companion object {
        fun load(dirs: List<File>, warden: Warden, trusted: Boolean = true): List<CommandTool> = dirs.flatMap { d ->
            d.listFiles { f -> f.extension == "json" }?.sortedBy { it.name }?.mapNotNull { f ->
                runCatching { CommandTool(KJ.decodeFromString(CommandToolDef.serializer(), f.readText()), warden, trusted) }.getOrNull()
                    // A bad or reserved name would fail every request (or crash opening the chat): skip that tool.
                    ?.takeIf { Regex("^[a-zA-Z0-9_-]{1,64}$").matches(it.name) && it.name !in RESERVED_TOOL_NAMES }
            } ?: emptyList()
        }
    }
}

/** Tools Kiln adds after the others: a command tool may not take their names. */
private val RESERVED_TOOL_NAMES = setOf("subagent", "qa_check", "tool_search")

// ---------------------------------------------------------------- MCP client

@Serializable
data class McpServerConfig(val name: String, val url: String, val token: String? = null, val enabled: Boolean = true)

/** Minimal MCP client over Streamable HTTP (JSON or SSE replies). */
class McpClient(private val cfg: McpServerConfig) {
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.MINUTES).build()
    private val ids = AtomicInteger()
    private var session: String? = null

    private suspend fun rpc(method: String, params: JsonObject?, notify: Boolean = false): JsonObject? = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("method", method)
            if (!notify) put("id", ids.incrementAndGet())
            if (params != null) put("params", params)
        }
        val req = Request.Builder().url(cfg.url).post(body.compact().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json, text/event-stream")
            .apply { cfg.token?.let { header("Authorization", "Bearer $it") }; session?.let { header("Mcp-Session-Id", it) } }.build()
        // Stop cancels the HTTP call too (a slow MCP tool held the turn for up to 5 minutes).
        // enqueue + invokeOnCancellation: cancellation fires at once (a job's completion handler only ran after the
        // blocking execute() returned).
        val call = http.newCall(req)
        val response = kotlinx.coroutines.suspendCancellableCoroutine<okhttp3.Response> { c ->
            c.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) { c.resumeWith(Result.success(response)) }
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { c.resumeWith(Result.failure(e)) }
            })
        }
        rpcBody(response, method, notify)
    }

    private fun rpcBody(response: okhttp3.Response, method: String, notify: Boolean): JsonObject? =
        response.use { r ->
            r.header("Mcp-Session-Id")?.let { session = it }
            if (notify) return@use null
            if (!r.isSuccessful) error("MCP ${cfg.name} $method: HTTP ${r.code}")
            val text = r.body.string()
            val json = if ((r.header("Content-Type") ?: "").contains("event-stream"))
                text.lines().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
                    .lastOrNull { it.contains("\"id\"") } ?: "{}" else text
            val o = parseJson(json) as JsonObject
            (o["error"] as? JsonObject)?.let { error("MCP ${cfg.name}: ${it.str("message")}") }
            o["result"] as? JsonObject
        }

    suspend fun connect(): List<Tool> {
        rpc("initialize", obj("protocolVersion" to "2025-06-18", "capabilities" to obj(),
            "clientInfo" to obj("name" to "kiln", "version" to "0.1")))
        rpc("notifications/initialized", null, notify = true)
        val tools = rpc("tools/list", obj())?.get("tools") as? JsonArray ?: return emptyList()
        return tools.mapNotNull { t ->
            val o = t as? JsonObject ?: return@mapNotNull null
            val remote = o.str("name") ?: return@mapNotNull null
            McpTool(this, cfg.name, remote, o.str("description") ?: "", (o["inputSchema"] as? JsonObject) ?: obj("type" to "object"))
        }
    }

    suspend fun call(tool: String, args: JsonObject): ToolResult {
        val r = rpc("tools/call", obj("name" to tool, "arguments" to args)) ?: return ToolResult.error("no result")
        val texts = mutableListOf<String>(); val images = mutableListOf<ByteArray>()
        (r["content"] as? JsonArray)?.forEach { c ->
            val o = c as? JsonObject ?: return@forEach
            when (o.str("type")) {
                "text" -> texts += o.str("text") ?: ""
                "image" -> o.str("data")?.let { images += Base64.getDecoder().decode(it) }
                else -> texts += o.compact()
            }
        }
        return ToolResult(texts.joinToString("\n"), images, isError = r["isError"]?.toString() == "true")
    }
}

class McpTool(private val client: McpClient, server: String, private val remote: String, desc: String, override val schema: JsonObject) : Tool {
    override var deferred = false
    override val name = "${server}__$remote".replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
    override val description = "[$server] $desc"
    override val traits = setOf(Trait.NEEDS_APPROVAL)
    override suspend fun run(ctx: ToolContext, input: JsonObject) = client.call(remote, input).let { it.copy(text = ctx.spill(it.text)) }
}

// ---------------------------------------------------------------- registry

/**
 * Every tool the agent can call this session, in one deterministic order (the
 * tool list is part of the cached prompt prefix: same tools, same bytes).
 */
class ToolRegistry(private val builtins: List<Tool>) {

    fun assemble(extra: List<Tool>, disabled: Set<String>, brokerReady: Boolean): List<Tool> {
        val seen = HashSet<String>()
        return (builtins + extra)
            .filter { it.name !in disabled && seen.add(it.name) }
            .filter { brokerReady || Trait.NEEDS_BROKER !in it.traits }
            .sortedBy { it.name }
    }

    /**
     * Tool schemas mark optional fields honestly (the harness accepts them missing). Strict mode
     * wants every property listed in `required`, so for it optional fields become required but
     * nullable — the documented strict-schema form — and the harness treats null as absent.
     */
    fun specs(tools: List<Tool>): List<ToolSpec> = tools.map { t ->
        // Strict form only for Kiln's own tools: MCP/command schemas may use keywords strict mode rejects.
        if (t.schema["additionalProperties"]?.toString() != "false" || t is McpTool || t is CommandTool) ToolSpec(t.name, t.description, t.schema, strict = false)
        else ToolSpec(t.name, t.description, strictForm(t.schema), strict = true)
    }

    private fun strictForm(schema: JsonObject): JsonObject {
        val props = schema["properties"] as? JsonObject ?: return schema
        val req = (schema["required"] as? JsonArray)?.map { (it as JsonPrimitive).content }?.toSet() ?: emptySet()
        val out = props.mapValues { (k, v) ->
            var p = v as JsonObject
            // Nested object items (todo items, multi_edit edits) get the same treatment.
            (p["items"] as? JsonObject)?.takeIf { it["properties"] != null }?.let { p = JsonObject(p + ("items" to strictForm(it))) }
            val type = p["type"] as? JsonPrimitive
            if (k in req || type == null) p
            else JsonObject(p + ("type" to JsonArray(listOf(type, JsonPrimitive("null"))))
                + listOfNotNull((p["enum"] as? JsonArray)?.let { "enum" to JsonArray(it + kotlinx.serialization.json.JsonNull) }))
        }
        return JsonObject(schema + ("properties" to JsonObject(out)) + ("required" to JsonArray(props.keys.map { JsonPrimitive(it) })))
    }

    /** Default policy from traits; per-tool overrides win (SPEC §4.3). */
    fun policy(tool: Tool, overrides: Map<String, String>): Policy {
        overrides[tool.name]?.let { return runCatching { Policy.valueOf(it.uppercase()) }.getOrDefault(Policy.ASK) }
        return if (Trait.NEEDS_APPROVAL in tool.traits || Trait.DESTRUCTIVE in tool.traits) Policy.ASK else Policy.ALLOW
    }

    companion object {
        fun validateNames(tools: List<Tool>) = tools.forEach {
            require(Regex("^[a-zA-Z0-9_-]{1,64}$").matches(it.name)) { "invalid tool name ${it.name}" }
        }
        @Suppress("unused") private fun JsonElement.asObj() = this as JsonObject
    }
}
