package app.kiln.tools

import app.kiln.core.a
import app.kiln.core.int
import app.kiln.core.str
import kotlinx.serialization.json.JsonObject
import java.io.File

private fun JsonObject.req(k: String) = str(k) ?: throw IllegalArgumentException("missing '$k'")

class ListDirTool : Tool {
    override val name = "list_dir"
    override val description = "List files under a directory of the project (recursive, skips build/ and .kiln/). Returns relative paths with sizes."
    override val schema = schema { str("path", "Directory relative to the project root; \"\" for the root.", required = false) }
    override val traits = setOf(Trait.READ_ONLY, Trait.PARALLEL_SAFE)
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val dir = ctx.project.resolve(input.str("path") ?: "")
        if (!dir.isDirectory) return ToolResult.error("not a directory: ${input.str("path")}")
        // "src" must not also list "src2/…".
        val files = ctx.project.files().filter { it.path == dir.path || it.path.startsWith(dir.path + java.io.File.separator) }
        if (files.isEmpty()) return ToolResult.ok("(empty)")
        return ToolResult.ok(files.joinToString("\n") { "${ctx.project.rel(it)}  (${it.length()} B)" }, plural(files.size, "file"))
    }
}

class GlobTool : Tool {
    override val name = "glob"
    override val description = "Find project files by glob pattern, e.g. \"src/**/*.kt\" or \"res/**/strings.xml\"."
    override val schema = schema { str("pattern", "Glob relative to the project root.") }
    override val traits = setOf(Trait.READ_ONLY, Trait.PARALLEL_SAFE)
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val m = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + input.req("pattern"))
        val hits = ctx.project.files().map { ctx.project.rel(it) }.filter { m.matches(java.nio.file.Paths.get(it)) }
        return ToolResult.ok(hits.joinToString("\n").ifEmpty { "(no matches)" }, plural(hits.size, "match", "matches"))
    }
}

class GrepTool : Tool {
    override val name = "grep"
    override val description = "Search project files with a regular expression. Returns file:line: text, with optional context lines. Use before editing to find every place that needs changing."
    override val schema = schema {
        str("pattern", "Regular expression (Kotlin/Java syntax).")
        str("glob", "Only files matching this glob, e.g. \"src/**/*.kt\". Empty for all.", required = false)
        int("context", "Lines of context around each hit (0–5).", required = false)
    }
    override val traits = setOf(Trait.READ_ONLY, Trait.PARALLEL_SAFE)
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val re = Regex(input.req("pattern"))
        val g = input.str("glob")?.takeIf { it.isNotBlank() }?.let { java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$it") }
        val c = (input.int("context") ?: 0).coerceIn(0, 5)
        val out = StringBuilder(); var hits = 0
        for (f in ctx.project.files()) {
            val rel = ctx.project.rel(f)
            if (g != null && !g.matches(java.nio.file.Paths.get(rel))) continue
            if (f.length() > 2_000_000) continue
            val lines = runCatching { f.readLines() }.getOrNull() ?: continue
            lines.forEachIndexed { i, l ->
                if (re.containsMatchIn(l)) {
                    hits++
                    if (c == 0) out.append("$rel:${i + 1}: $l\n")
                    else { (maxOf(0, i - c)..minOf(lines.size - 1, i + c)).forEach { j -> out.append("$rel:${j + 1}${if (j == i) ":" else "-"} ${lines[j]}\n") }; out.append("--\n") }
                }
            }
        }
        return ToolResult.ok(ctx.spill(out.toString().ifEmpty { "(no matches)" }), "$hits hits")
    }
}

class ReadFileTool : Tool {
    override val name = "read_file"
    override val description = "Read a project file with line numbers. Read a file before editing it — edit_file refuses edits to files you haven't read (or that changed since)."
    override val schema = schema {
        str("path", "File relative to the project root.")
        int("offset", "First line (1-based); 1 for the start.", required = false)
        int("limit", "Max lines; 0 for up to 2000.", required = false)
    }
    override val traits = setOf(Trait.READ_ONLY, Trait.PARALLEL_SAFE)
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val f = ctx.project.resolve(input.req("path"))
        if (!f.isFile) return ToolResult.error("no such file: ${input.str("path")}")
        val lines = f.readLines()
        val from = ((input.int("offset") ?: 1) - 1).coerceIn(0, maxOf(0, lines.size))
        val n = (input.int("limit") ?: 0).let { if (it <= 0) 2000 else it }
        ctx.state.readStamps[ctx.project.rel(f)] = f.lastModified()
        val body = lines.drop(from).take(n).mapIndexed { i, l -> "%5d\t%s".format(from + i + 1, l) }.joinToString("\n")
        val more = if (from + n < lines.size) "\n… ${lines.size - from - n} more lines (use offset)" else ""
        return ToolResult.ok(ctx.spill(body + more), "${ctx.project.rel(f)} (${plural(lines.size, "line")})")
    }
}

class WriteFileTool : Tool {
    override fun precheck(input: JsonObject): String? = misplacedSource(input.str("path"))
    override val name = "write_file"
    override val description = "Create a file or replace its whole content. For changes to an existing file prefer edit_file (smaller, safer)."
    override val schema = schema {
        str("path", "File relative to the project root (parent dirs are created).")
        str("content", "The complete file content.")
    }
    override val traits = emptySet<Trait>()
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val rel = input.req("path")
        misplacedSource(rel)?.let { return ToolResult.error(it) }
        val f = ctx.project.resolveWritable(rel)
        val existed = f.exists()
        f.parentFile?.mkdirs()
        f.writeText(input.req("content"))
        ctx.state.readStamps[ctx.project.rel(f)] = f.lastModified()
        return ToolResult.ok("${if (existed) "replaced" else "created"} ${ctx.project.rel(f)} (${f.length()} B)")
    }
}

class EditFileTool : Tool {
    override val name = "edit_file"
    override val description = "Replace exact text in a file. old_text must appear exactly once (include surrounding lines to make it unique) unless replace_all is true. The file must have been read with read_file and not changed since."
    override val schema = schema {
        str("path", "File relative to the project root.")
        str("old_text", "Exact existing text, including indentation.")
        str("new_text", "Replacement text.")
        bool("replace_all", "Replace every occurrence instead of exactly one.", required = false)
    }
    override val traits = emptySet<Trait>()
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val f = ctx.project.resolveWritable(input.req("path"))
        if (!f.isFile) return ToolResult.error("no such file: ${input.str("path")}")
        val rel = ctx.project.rel(f)
        val seen = ctx.state.readStamps[rel] ?: return ToolResult.error("read $rel with read_file before editing it")
        if (seen != f.lastModified()) return ToolResult.error("$rel changed since you read it — read it again")
        // Models write "\n"; a CRLF file is matched and saved in its own line endings.
        val raw = f.readText(); val crlf = "\r\n" in raw
        val text = if (crlf) raw.replace("\r\n", "\n") else raw
        val old = input.req("old_text").replace("\r\n", "\n"); val new = (input.str("new_text") ?: "").replace("\r\n", "\n")
        val count = occurrences(text, old)
        val all = input["replace_all"]?.toString() == "true"
        when {
            count == 0 -> return ToolResult.error("old_text not found in $rel (check whitespace/indentation; re-read the file)")
            count > 1 && !all -> return ToolResult.error("old_text matches $count places in $rel — add surrounding lines to make it unique, or set replace_all")
        }
        val edited = if (all) text.replace(old, new) else text.replaceFirst(old, new)
        f.writeText(if (crlf) edited.replace("\n", "\r\n") else edited)
        ctx.state.readStamps[rel] = f.lastModified()
        return ToolResult.ok("edited $rel (${if (all) count else 1} replacement${if (all && count > 1) "s" else ""})")
    }
}

class MultiEditTool : Tool {
    override val name = "multi_edit"
    override val description = "Apply several exact-text edits to one file atomically, in order (each like edit_file). All succeed or none are written."
    override val schema = schema {
        str("path", "File relative to the project root.")
        objList("edits", "Edits applied in order.", { str("old_text", "Exact existing text."); str("new_text", "Replacement.") })
    }
    override val traits = emptySet<Trait>()
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val f = ctx.project.resolveWritable(input.req("path"))
        val rel = ctx.project.rel(f)
        if (!f.isFile) return ToolResult.error("no such file: $rel")
        val seen = ctx.state.readStamps[rel] ?: return ToolResult.error("read $rel with read_file before editing it")
        if (seen != f.lastModified()) return ToolResult.error("$rel changed since you read it — read it again")
        val raw = f.readText(); val crlf = "\r\n" in raw
        var text = if (crlf) raw.replace("\r\n", "\n") else raw
        input.a("edits")?.forEachIndexed { i, e ->
            val o = e as JsonObject
            val old = o.req("old_text").replace("\r\n", "\n")
            val n = occurrences(text, old)
            if (n != 1) return ToolResult.error("edit #${i + 1}: old_text matches $n places (must be exactly 1); nothing written")
            text = text.replaceFirst(old, (o.str("new_text") ?: "").replace("\r\n", "\n"))
        }
        f.writeText(if (crlf) text.replace("\n", "\r\n") else text)
        ctx.state.readStamps[rel] = f.lastModified()
        return ToolResult.ok("applied ${input.a("edits")?.size ?: 0} edits to $rel")
    }
}

class MoveTool : Tool {
    override val name = "move"
    override val description = "Move or rename a project file or directory."
    override val schema = schema { str("from", "Existing path."); str("to", "New path.") }
    override val traits = emptySet<Trait>()
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val a = ctx.project.resolveWritable(input.req("from")); val b = ctx.project.resolveWritable(input.req("to"))
        if (!a.exists()) return ToolResult.error("no such path: ${input.str("from")}")
        if (b.exists()) return ToolResult.error("destination exists: ${input.str("to")}")
        b.parentFile?.mkdirs()
        if (!a.renameTo(b)) return ToolResult.error("move failed")
        return ToolResult.ok("moved ${ctx.project.rel(b)}")
    }
}

class DeleteTool : Tool {
    override val name = "delete"
    override val description = "Delete a project file or directory (a checkpoint is a safer first step for big removals)."
    override val schema = schema { str("path", "Path relative to the project root.") }
    override val traits = setOf(Trait.DESTRUCTIVE)
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val f = ctx.project.resolveWritable(input.req("path"))
        require(f.canonicalPath != ctx.project.dir.canonicalPath) { "refusing to delete the project root" }
        require(ctx.project.rel(f) !in setOf("kiln.json", "AndroidManifest.xml")) { "kiln.json and AndroidManifest.xml are required" }
        if (!f.exists()) return ToolResult.error("no such path")
        f.deleteRecursively()
        return ToolResult.ok("deleted ${input.str("path")}")
    }
}

class ReadOutputTool : Tool {
    override val name = "read_output"
    override val description = "Read the full text of a tool output that was cut short (outputs say \"saved as out-…\"). Optionally only lines matching a regex."
    override val schema = schema {
        str("id", "The output id, e.g. out-12.")
        int("offset", "First line (1-based).", required = false)
        int("limit", "Max lines; 0 for 400.", required = false)
        str("grep", "Only lines matching this regex; empty for all.", required = false)
    }
    override val traits = setOf(Trait.READ_ONLY, Trait.PARALLEL_SAFE)
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val id = input.req("id").replace(Regex("[^a-z0-9-]"), "")
        val f = File(ctx.spillDir, "$id.txt")
        if (!f.isFile) return ToolResult.error("no saved output $id")
        var lines = f.readLines().mapIndexed { i, l -> i + 1 to l }
        input.str("grep")?.takeIf { it.isNotBlank() }?.let { g -> val re = Regex(g); lines = lines.filter { re.containsMatchIn(it.second) } }
        val from = ((input.int("offset") ?: 1) - 1).coerceAtLeast(0)
        val n = (input.int("limit") ?: 0).let { if (it <= 0) 400 else it }
        return ToolResult.ok(lines.drop(from).take(n).joinToString("\n") { "%5d\t%s".format(it.first, it.second) })
    }
}

/** Non-overlapping occurrences of [needle] (what replace() will touch), without copying the text. */
internal fun occurrences(text: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var n = 0; var at = text.indexOf(needle)
    while (at >= 0) { n++; at = text.indexOf(needle, at + needle.length) }
    return n
}

/** Kotlin outside src/ is never compiled: say so before the file is written, with the right path. */
internal fun misplacedSource(path: String?): String? {
    val p = path?.replace('\\', '/')?.trimStart('/') ?: return null
    if (!p.endsWith(".kt") || p.startsWith("src/")) return null
    return "Kotlin sources must be under src/ (the app's package directory, e.g. src/kiln/app/<name>/$p) or they won't be compiled. " +
        "Write it there instead — project_info shows the package."
}
