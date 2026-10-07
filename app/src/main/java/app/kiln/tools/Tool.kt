package app.kiln.tools

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
) {
    /** Anthropic-shaped tool_result content blocks. */
    fun blocks(): JsonArray = arrOf(listOf(obj("type" to "text", "text" to text.ifBlank { "(no output)" })) +
        images.map { obj("type" to "image", "source" to obj("type" to "base64", "media_type" to "image/png",
            "data" to Base64.getEncoder().encodeToString(it))) })

    companion object {
        fun ok(text: String, summary: String? = null) = ToolResult(text, summary = summary ?: text.lineSequence().firstOrNull()?.take(140) ?: "")
        fun error(text: String) = ToolResult(text, isError = true)
    }
}

/** Mutable per-session state tools share (read stamps, log markers, todo list, last build). */
class SessionState {
    /** path → lastModified seen by read_file; edit_file refuses stale edits. */
    val readStamps = mutableMapOf<String, Long>()
    /** package → logcat time marker of the last read. */
    val logMarkers = mutableMapOf<String, String>()
    var todos: List<Todo> = emptyList()
    var lastBuild: BuildResult? = null
    var launchMarker: Pair<String, String>? = null
    data class Todo(val text: String, val status: String)
}

/** Everything a tool can reach. Built per tool call by the agent loop. */
interface ToolContext {
    val project: Project
    val sessionId: String
    val state: SessionState
    /** Live progress line on the tool's activity card. */
    fun progress(line: String)
    /** Ask the user a question with options (blocks until answered). */
    suspend fun ask(question: String, options: List<String>): String
    /** Text over [maxChars] is cut to head + tail and saved; the agent can fetch the rest with read_output. */
    fun spill(text: String, maxChars: Int = 24_000): String
    val spillDir: File
}

interface Tool {
    val name: String
    /** Written for the model: when to use it and what comes back. */
    val description: String
    val schema: JsonObject
    val traits: Set<Trait>
    val timeoutMs: Long get() = 120_000
    suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult
}

/** Schema DSL: `schema { str("path", "File path"); int("line", "…", required = false) }`. */
class SchemaBuilder {
    private val props = linkedMapOf<String, JsonElement>()
    private val required = mutableListOf<String>()
    private fun add(name: String, spec: JsonObject, req: Boolean) { props[name] = spec; if (req) required += name }
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
