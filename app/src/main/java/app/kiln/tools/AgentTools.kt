package app.kiln.tools

import app.kiln.core.a
import app.kiln.core.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

private fun JsonObject.req(k: String) = str(k) ?: throw IllegalArgumentException("missing '$k'")

class TodoTool : Tool {
    override val name = "todo"
    override val description = "Keep a visible checklist for the task. Send the complete list each time; status is pending | in_progress | done. Use it for anything with 3+ steps."
    override val schema = schema {
        objList("items", "The full checklist.", { str("text", "Step."); str("status", "Status.", enum = listOf("pending", "in_progress", "done")) })
    }
    override val traits = setOf(Trait.READ_ONLY)
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        ctx.state.todos = input.a("items")?.map { (it as JsonObject).let { o -> SessionState.Todo(o.req("text"), o.str("status") ?: "pending") } } ?: emptyList()
        val done = ctx.state.todos.count { it.status == "done" }
        return ToolResult.ok(ctx.state.todos.joinToString("\n") { "[${when (it.status) { "done" -> "x"; "in_progress" -> "~"; else -> " " }}] ${it.text}" },
            "$done/${ctx.state.todos.size} done")
    }
}

class AskUserTool : Tool {
    override val name = "ask_user"
    override val description = "Ask the user a question when a decision is genuinely theirs (e.g. which of two designs). Give 2–4 short options; they may also answer freely. Don't ask what you can decide sensibly yourself."
    override val schema = schema { str("question", "The question."); strList("options", "2–4 short options.", required = false) }
    override val traits = setOf(Trait.READ_ONLY)
    override val timeoutMs = 24 * 3_600_000L
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val answer = ctx.ask(input.req("question"), input.a("options")?.map { (it as JsonPrimitive).content } ?: emptyList())
        return ToolResult.ok("user answered: $answer")
    }
}

/**
 * Checkpoints: a copy of the project's sources (no build/, no .kiln/) in
 * .kiln/checkpoints/<n>-<label>/. restore copies one back (taking a safety
 * checkpoint first). Plain file copies — there is no git on the phone.
 */
class CheckpointTool : Tool {
    override val name = "checkpoint"
    override val description = "Save a restorable snapshot of the project's files before a risky change. Returns its id."
    override val schema = schema { str("label", "Short label, e.g. before-nav-refactor.") }
    override val traits = emptySet<Trait>()
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val id = snapshot(ctx, input.req("label"))
        return ToolResult.ok("checkpoint $id saved")
    }

    companion object {
        fun dir(ctx: ToolContext) = dir(ctx.project)
        fun dir(project: app.kiln.build.Project) = File(project.kilnDir, "checkpoints").apply { mkdirs() }
        fun snapshot(ctx: ToolContext, label: String): String = snapshot(ctx.project, label)
        fun snapshot(project: app.kiln.build.Project, label: String): String {
            val n = (dir(project).listFiles()?.mapNotNull { it.name.substringBefore('-').toIntOrNull() }?.maxOrNull() ?: 0) + 1
            val id = "$n-" + label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
            val target = File(dir(project), id)
            for (f in project.files()) { val out = File(target, project.rel(f)); out.parentFile?.mkdirs(); f.copyTo(out) }
            return id
        }
    }
}

class RestoreTool : Tool {
    override val name = "restore"
    override val description = "Restore the project's files from a checkpoint (a safety checkpoint of the current state is taken first). With an empty id, lists checkpoints."
    override val schema = schema { str("id", "Checkpoint id, or empty to list.", required = false) }
    override val traits = setOf(Trait.DESTRUCTIVE)
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val all = CheckpointTool.dir(ctx).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.substringBefore('-').toIntOrNull() ?: 0 } ?: emptyList()
        val id = input.str("id")?.trim().orEmpty()
        if (id.isEmpty()) return ToolResult.ok(all.joinToString("\n") { it.name }.ifEmpty { "(no checkpoints)" })
        val src = all.firstOrNull { it.name == id || it.name.startsWith("$id-") } ?: return ToolResult.error("no checkpoint $id")
        val safety = CheckpointTool.snapshot(ctx, "before-restore")
        ctx.project.files().forEach { it.delete() }
        src.walkTopDown().filter { it.isFile }.forEach { f -> val out = File(ctx.project.dir, f.relativeTo(src).path); out.parentFile?.mkdirs(); f.copyTo(out, true) }
        ctx.state.readStamps.clear()
        return ToolResult.ok("restored ${src.name} (current state saved as $safety)")
    }
}

class MemoryTool : Tool {
    override val name = "project_memory"
    override val description = "The project's long-term notes (.kiln/memory.md), shown to you at the start of every session: decisions, conventions, known issues, what the user wants. read returns it; write replaces it — keep it short and current."
    override val schema = schema { str("action", "read or write.", enum = listOf("read", "write")); str("content", "New content for write; empty for read.", required = false) }
    override val traits = emptySet<Trait>()
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val f = ctx.project.memoryFile
        val action = when (input.req("action").lowercase()) {
            "write", "save", "set", "update", "replace" -> "write"
            "read", "get", "load", "show" -> "read"
            else -> return ToolResult.error("action must be read or write")
        }
        return if (action == "write") {
            f.parentFile?.mkdirs(); f.writeText(input.str("content") ?: ""); ToolResult.ok("memory saved (${f.length()} B)")
        } else ToolResult.ok(f.takeIf { it.isFile }?.readText()?.ifBlank { null } ?: "(empty)")
    }
}
