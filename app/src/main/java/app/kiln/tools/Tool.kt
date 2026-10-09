package app.kiln.tools

import kotlinx.serialization.json.JsonPrimitive
import app.kiln.build.BuildResult
import app.kiln.build.Project
import app.kiln.core.arrOf
import app.kiln.core.obj
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Base64

/** How the harness may treat a tool (SPEC §4.1). Policy and scheduling key off these, never off names. */
enum class Trait { READ_ONLY, PARALLEL_SAFE, DESTRUCTIVE, NEEDS_APPROVAL, NEEDS_BROKER, RETURNS_IMAGE, LONG_RUNNING }

/** A tool result: text and/or images, plus a one-line summary for the activity card. */
data class ToolResult(
    val text: String,
    val images: List<ByteArray> = emptyList(),
    val isError: Boolean = false,
    val summary: String = text.lineSequence().firstOrNull()?.take(140) ?: "",
    /** A recording (MP4 path) to show with the result, e.g. a QA run on the test display. */
    val video: String? = null,
    /** Shown to the user when the step is expanded, never sent to the model (e.g. the QA agent's own steps). */
    val detail: String? = null,
    /** What the screen looked like after this step, shown in the chat only (never sent to the model). */
    val preview: ByteArray? = null,
) {
    /** Anthropic-shaped tool_result content blocks. */
    fun blocks(): JsonArray = arrOf(listOf(obj("type" to "text", "text" to text.ifBlank { "(no output)" })) +
        images.map { obj("type" to "image", "source" to obj("type" to "base64", "media_type" to mediaType(it),
            "data" to Base64.getEncoder().encodeToString(it))) })

    companion object {
        /** From the bytes: an MCP tool's JPEG declared as PNG is rejected by the API, and stays in history. */
        internal fun mediaType(b: ByteArray): String = when {
            b.size > 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
            b.size > 11 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
            b.size > 3 && String(b, 0, 4, Charsets.ISO_8859_1) == "GIF8" -> "image/gif"
            else -> "image/png"
        }
        fun ok(text: String, summary: String? = null) = ToolResult(text, summary = summary ?: text.lineSequence().firstOrNull()?.take(140) ?: "")
        fun error(text: String) = ToolResult(text, isError = true)
    }
}

/** Mutable per-session state tools share (read stamps, log markers, todo list, last build). */
class SessionState {
    /** path → lastModified seen by read_file; edit_file refuses stale edits. */
    val readStamps: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()   // parallel read_file calls
    /** package → logcat time marker of the last read. */
    val logMarkers = mutableMapOf<String, String>()
    var todos: List<Todo> = emptyList()
    var lastBuild: BuildResult? = null
        // Only the latest result's own APK: after a check (no APK) the code may have changed, so install needs a new build.
        set(v) { field = v; lastApk = v?.takeIf { it.ok }?.apk }
    /** The APK of the latest build, if it succeeded. */
    var lastApk: String? = null
        private set
    var launchMarker: Pair<String, String>? = null
    /** Deferred tools tool_search has loaded into this session. */
    val loadedTools: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    data class Todo(val text: String, val status: String)
}

/** Everything a tool can reach. Built per tool call by the agent loop. */
interface ToolContext {
    val project: Project
    val sessionId: String
    val state: SessionState
    /** Live progress line on the tool's activity card. */
    fun progress(line: String)
    /** The steps a helper agent is taking inside this call (qa_check), shown nested under it in the chat. */
    fun children(steps: List<app.kiln.agent.Activity>) {}
    /** The calling request may change nothing (plan mode): helpers it starts get read-only tools too. */
    val readOnly: Boolean get() = false
    /** Ask the user a question with options (blocks until answered). */
    suspend fun ask(question: String, options: List<String>): String
    /** Ask up to 4 structured questions at once (Claude Code style); one answer per question, multi-select answers joined by ", ". */
    suspend fun askMany(questions: List<AskQ>): List<String> = questions.map { q -> ask(q.question, q.options.map { it.label }) }
    /** Show the look picker (themes + styles) for a new app; answers "theme=…; style=…", "default" or the user's words. */
    suspend fun chooseLook(appName: String?): String = ask("Which look should ${appName ?: "this app"} have?",
        listOf("Kiln default", "Ocean Depths", "Sunset Boulevard", "Modern Minimalist")).let { a ->
            app.kiln.tools.Looks.themes.firstOrNull { it.name.equals(a, true) }?.let { app.kiln.tools.Looks.answer(it.name, "Flat") }
                ?: if (a.equals("Kiln default", true)) app.kiln.tools.Looks.DEFAULT else a }
    /** Text over [maxChars] is cut to head + tail and saved; the agent can fetch the rest with read_output. */
    fun spill(text: String, maxChars: Int = 24_000): String
    val spillDir: File
    /** Spend made on this request's behalf (a subagent's model calls) — counts toward the chat's cap. */
    fun addCost(usd: Double) {}
    /** What the calling chat has spent so far: a helper's spending cap counts it too. */
    val spent: Double get() = 0.0
}

interface Tool {
    val name: String
    /** Written for the model: when to use it and what comes back. */
    val description: String
    val schema: JsonObject
    val traits: Set<Trait>
    val timeoutMs: Long get() = 120_000
    /** Held back from the model until `tool_search` finds it (large MCP servers). */
    val deferred: Boolean get() = false
    suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult
    /** Rejects input that can never be right before anyone is asked to approve it (null = fine). */
    fun precheck(input: JsonObject): String? = null
}

/** Schema DSL: `schema { str("path", "File path"); int("line", "…", required = false) }`. */
class SchemaBuilder {
    private val props = linkedMapOf<String, JsonElement>()
    private val required = mutableListOf<String>()
    private fun add(name: String, spec: JsonObject, req: Boolean) { props[name] = spec; if (req) required += name }
    /** A property with a hand-written JSON schema (arrays of objects with free-form keys, …). */
    fun raw(name: String, spec: JsonObject, required: Boolean = true) = add(name, spec, required)
    fun str(name: String, desc: String, required: Boolean = true, enum: List<String>? = null) =
        add(name, obj("type" to "string", "description" to desc, "enum" to enum), required)
    fun int(name: String, desc: String, required: Boolean = true) = add(name, obj("type" to "integer", "description" to desc), required)
    fun bool(name: String, desc: String, required: Boolean = true) = add(name, obj("type" to "boolean", "description" to desc), required)
    fun strList(name: String, desc: String, required: Boolean = true) =
        add(name, obj("type" to "array", "items" to obj("type" to "string"), "description" to desc), required)
    fun objList(name: String, desc: String, item: SchemaBuilder.() -> Unit, required: Boolean = true) =
        add(name, obj("type" to "array", "items" to SchemaBuilder().apply(item).build(), "description" to desc), required)
    fun build(): JsonObject = obj("type" to "object", "properties" to JsonObject(props),
        "required" to arrOf(required), "additionalProperties" to false)
}

fun schema(block: SchemaBuilder.() -> Unit): JsonObject = SchemaBuilder().apply(block).build()

/** "1 file", "3 files" — tool summaries are shown to people. */
fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

/** What the harness checks before running a tool: required fields, unknown fields, enums, primitive types. */
fun validateInput(tool: Tool, input: JsonObject): String? = validateAgainst(tool.schema, input, "")

private fun validateAgainst(schema: JsonObject, input: JsonObject, at: String): String? {
    val props = schema["properties"] as? JsonObject ?: return null
    val required = (schema["required"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList()
    val missing = required.filter { it !in input }
    if (missing.isNotEmpty()) return "missing required input: ${missing.joinToString { at + it }}"
    val unknown = input.keys.filter { it !in props }
    if (unknown.isNotEmpty() && schema["additionalProperties"]?.toString() == "false") return "unknown input: ${unknown.joinToString()}"
    // Providers without strict tool schemas (local models, most OpenAI-compatible proxies) can send
    // any value; check enums and primitive types so a bad call fails loudly instead of misbehaving.
    for ((k, v) in input) {
        val spec = props[k] as? JsonObject ?: continue
        if (v is kotlinx.serialization.json.JsonNull && k !in required) continue
        val prim = v as? JsonPrimitive
        (spec["enum"] as? JsonArray)?.map { (it as JsonPrimitive).content }?.let { allowed ->
            if (prim == null || prim.content !in allowed) return "$at$k must be one of ${allowed.joinToString { "\"$it\"" }}, got ${v}"
        }
        val ok = when ((spec["type"] as? JsonPrimitive)?.content) {
            "string" -> prim?.isString == true
            "integer" -> prim != null && !prim.isString && prim.content.toLongOrNull() != null
            "number" -> prim != null && !prim.isString && prim.content.toDoubleOrNull() != null
            "boolean" -> prim != null && !prim.isString && prim.content in setOf("true", "false")
            "array" -> v is JsonArray
            "object" -> v is JsonObject
            else -> true
        }
        if (!ok) return "$at$k must be a ${(spec["type"] as JsonPrimitive).content}, got $v"
        // Arrays of objects (todo items, multi_edit edits): check each element too.
        val item = spec["items"] as? JsonObject
        if (v is JsonArray && item?.get("properties") != null) v.forEachIndexed { i, e ->
            if (e !is JsonObject) return "$at$k[$i] must be an object"
            validateAgainst(item, e, "$at$k[$i].")?.let { return it }
        }
    }
    return null
}

/** One choice in an [AskQ]: a short label, what it means, and an optional preview (mockup, code) shown when focused. */
data class AskOption(val label: String, val description: String = "", val preview: String? = null)

/** A structured question for the user: the question, a short header chip, 2–4 options, single or multi-select. */
data class AskQ(val question: String, val header: String = "", val options: List<AskOption>, val multiSelect: Boolean = false)
