package app.kiln.tools

import app.kiln.core.a
import app.kiln.core.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

private fun JsonObject.req(k: String) = str(k) ?: throw IllegalArgumentException("missing '$k'")

class TodoTool : Tool {
    override val name = "todo"
    override val description = "Your plan, shown to the user as a live checklist. Send the complete list each time; status is " +
        "pending | in_progress | done.\n" +
        "How to plan:\n" +
        "- 4–10 items, each a concrete outcome you can check off — say what will be true, not an activity: " +
        "\"Expenses screen: list grouped by date, search, swipe to delete\", not \"work on expenses\" or \"load skills\".\n" +
        "- In build order: data (models, stores) → one item per screen or feature → builds and runs → one item per user journey " +
        "tested with test_flow → qa_check passes.\n" +
        "- Exactly one item in_progress: the one you're on now.\n" +
        "- Mark an item done the moment it's finished and checked, in the same step as your next action — don't save ticks for later.\n" +
        "- Add an item when you discover work; if one becomes unnecessary, mark it done and say why in its text.\n" +
        "Example (expense tracker): [done] Data: Expense (KDate), Repo with KCollection + KStore · [in_progress] Home: month total, " +
        "donut by category, latest 5, budget warning · [pending] Expenses: grouped by date, search, swipe-to-delete, tap to edit · " +
        "[pending] Add: form with validation · [pending] Settings: budget, currency, reminder · [pending] Builds and runs on the phone · " +
        "[pending] Journeys tested: add, edit, delete, search, settings · [pending] qa_check passes."
    override val schema = schema {
        objList("items", "The full checklist.", { str("text", "An outcome you can check off."); str("status", "Status.", enum = listOf("pending", "in_progress", "done")) })
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
    override val description = "Ask the user 1–4 questions at once, only when you're blocked on a decision that is genuinely theirs " +
        "(which features, a design direction, a trade-off) and you can't settle it from the request or sensible defaults. " +
        "Each question has a short header (≤12 chars) and 2–4 distinct options with a label (1–5 words) and a description of " +
        "what choosing it means. Put the option you recommend first and end its label with \"(Recommended)\". Use multiSelect " +
        "when choices aren't exclusive. Give options a preview (ASCII layout mockup, code snippet) when the user should compare " +
        "how they look. The user can always answer in their own words, so don't add an \"Other\" option. Never ask what you can decide."
    override val schema = schema {
        objList("questions", "1–4 questions.", {
            str("question", "The full question, ending with a question mark.")
            str("header", "A very short label shown as a chip, e.g. \"Layout\", \"Sync\" (≤12 chars).")
            objList("options", "2–4 distinct choices.", {
                str("label", "1–5 words.")
                str("description", "What choosing it means: trade-offs, consequences.")
                str("preview", "Optional mockup or code shown when this option is focused (single-select only).", required = false)
            })
            bool("multiSelect", "Let the user pick several.", required = false)
        })
    }
    override val traits = setOf(Trait.READ_ONLY)
    override val timeoutMs = 24 * 3_600_000L
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val qs = input.a("questions")?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val opts = o.a("options")?.mapNotNull { op -> (op as? JsonObject)?.let { AskOption(it.str("label") ?: return@let null, it.str("description").orEmpty(), it.str("preview")) } }.orEmpty()
            AskQ(o.str("question") ?: return@mapNotNull null, o.str("header").orEmpty().take(16), opts.take(4), o["multiSelect"]?.let { (it as? JsonPrimitive)?.content == "true" } ?: false)
        }?.take(4).orEmpty()
        if (qs.isEmpty()) return ToolResult.error("give 1–4 `questions`, each with a question, a header and 2–4 options")
        val answers = ctx.askMany(qs)
        val lines = qs.mapIndexed { i, q -> "\"${q.question}\" = \"${answers.getOrNull(i).orEmpty().ifBlank { "(no answer — decide sensibly and say what you chose)" }}\"" }
        return ToolResult.ok("The user answered your questions:\n" + lines.joinToString("\n") + "\nContinue with these answers in mind.",
            "Asked ${qs.size} question${if (qs.size > 1) "s" else ""}")
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
