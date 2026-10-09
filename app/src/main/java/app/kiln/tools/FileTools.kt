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
        f.writeText(formatted(f, input.req("content")))
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
        // Files are formatted on save, so the agent's copy may be indented differently: match ignoring indentation.
        val loose = if (count == 0) importEdit(text, old, new) ?: replaceIgnoringIndent(text, old, new) ?: splitImportEdit(text, old, new) else null
        when {
            count == 0 && loose == null -> return ToolResult.error("old_text not found in $rel." + wholeFileHint(text, old) + closestMatch(text, old))
            count > 1 && !all -> return ToolResult.error("old_text matches $count places in $rel — add surrounding lines to make it unique, or set replace_all")
        }
        val edited = loose ?: if (all) text.replace(old, new) else text.replaceFirst(old, new)
        val saved = formatted(f, edited)
        f.writeText(if (crlf) saved.replace("\n", "\r\n") else saved)
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
            val replacement = (o.str("new_text") ?: "").replace("\r\n", "\n")
            val loose = if (n == 0) importEdit(text, old, replacement) ?: replaceIgnoringIndent(text, old, replacement) ?: splitImportEdit(text, old, replacement) else null
            if (n != 1 && loose == null) return ToolResult.error("edit #${i + 1}: old_text matches $n places (must be exactly 1); nothing written." +
                (if (n == 0) closestMatch(text, old) else " Add surrounding lines to make it unique."))
            text = loose ?: text.replaceFirst(old, replacement)
        }
        text = formatted(f, text)
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
    val p = path?.replace('\\', '/')?.removePrefix("./")?.trimStart('/') ?: return null
    // Java isn't compiled by Kiln's on-device build (kotlinc only): it would pass the build and crash at runtime.
    if (p.endsWith(".java")) return "Kiln builds Kotlin only — a .java file wouldn't be compiled. Write it as a .kt file under src/."
    if (!p.endsWith(".kt") || p.startsWith("src/")) return null
    return "Kotlin sources must be under src/ (the app's package directory, e.g. src/kiln/app/<name>/$p) or they won't be compiled. " +
        "Write it there instead — project_info shows the package."
}

/**
 * When a failed edit's old_text is (nearly) the whole file from its package line — the model rewriting a
 * file it remembers, not the one on disk (run 11: MainActivity without the Theme line the look picker
 * added) — say to rewrite it with write_file, keeping what Kiln added. Empty otherwise.
 */
internal fun wholeFileHint(text: String, old: String): String {
    if (!old.trimStart().startsWith("package ") || old.lines().size < text.lines().size * 0.6) return ""
    val theme = text.lines().firstOrNull { "override fun Theme(" in it }?.trim()
    return " It looks like you meant to replace the whole file: read_file it and send the full new content with write_file" +
        (if (theme != null) ", keeping this line Kiln added for the app's look:\n  $theme\n" else ".\n")
}

/**
 * Where [old] most nearly appears in [text] — the lines to copy exactly — so a failed edit costs one
 * retry instead of a re-read of the whole file. Empty when nothing is close.
 */
internal fun closestMatch(text: String, old: String): String {
    val lines = text.lines()
    val want = old.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (want.isEmpty() || lines.isEmpty()) return ""
    fun sim(a: String, b: String): Double {
        if (a == b) return 1.0
        val x = a.windowed(2).toSet(); val y = b.windowed(2).toSet()
        return if (x.isEmpty() || y.isEmpty()) 0.0 else 2.0 * (x intersect y).size / (x.size + y.size)
    }
    var best = -1; var bestScore = 0.0
    for (i in lines.indices) {
        var score = 0.0; var j = i; var k = 0
        while (k < want.size && j < lines.size) {
            val l = lines[j].trim()
            if (l.isEmpty()) { j++; continue }
            score += sim(l, want[k]); j++; k++
        }
        if (score / want.size > bestScore) { bestScore = score / want.size; best = i }
    }
    if (best < 0 || bestScore < 0.5) return " Nothing similar found — read the file again."
    val end = minOf(lines.size, best + minOf(want.size + 2, 30))
    val sameIgnoringIndent = lines.subList(best, end).map { it.trim() }.filter { it.isNotEmpty() }.take(want.size) == want
    return (if (sameIgnoringIndent) " The text is there with different indentation." else " Closest match") +
        " (lines ${best + 1}–$end) — copy it exactly:\n" + (best until end).joinToString("\n") { "${it + 1}\t${lines[it]}" }
}

/** Kotlin files are formatted on every save (KotlinFormat); anything else is written as given. */
internal fun formatted(f: java.io.File, text: String): String =
    if (f.extension == "kt") runCatching { app.kiln.build.KotlinFormat.format(text) }.getOrDefault(text) else text

/**
 * [old] as whole lines, compared without their leading whitespace: when that finds exactly one place,
 * replace it with [new], re-indented by the same offset. Null when there's no single such place.
 */
internal fun replaceIgnoringIndent(text: String, old: String, new: String): String? {
    val want = old.trimEnd('\n').split("\n")
    if (want.all { it.isBlank() }) return null
    val lines = text.split("\n")
    val hits = (0..lines.size - want.size).filter { s0 -> want.indices.all { k -> lines[s0 + k].trim() == want[k].trim() } }
    if (hits.size != 1) return null
    val at = hits[0]
    fun indentOf(l: String) = l.length - l.trimStart().length
    val firstWanted = want.indexOfFirst { it.isNotBlank() }
    val delta = indentOf(lines[at + firstWanted]) - indentOf(want[firstWanted])
    val replacement = new.trimEnd('\n').split("\n").map { l ->
        if (l.isBlank()) "" else " ".repeat(maxOf(0, indentOf(l) + delta)) + l.trimStart()
    }
    return (lines.subList(0, at) + (if (new.isEmpty()) emptyList() else replacement) + lines.subList(at + want.size, lines.size)).joinToString("\n")
}

/**
 * An edit whose old and new text are only import lines, applied as a set change: the old imports are
 * removed, the new ones added, wherever they sit. Saving sorts imports, so the agent's remembered
 * order can't be relied on. Null when the edit isn't purely about imports.
 */
/**
 * An edit that spans import lines and code (run 16 missed three times: saving sorts imports and Kiln adds
 * some, so the remembered import block never matches): the code part is matched on its own (exactly or
 * ignoring indentation) and replaced, and the import part is applied as a set change. Null if the code
 * part doesn't match either, or there are no import lines.
 */
internal fun splitImportEdit(text: String, old: String, new: String): String? {
    fun split(s: String): Pair<List<String>, String> {
        val lines = s.lines()
        val imp = lines.filter { it.trim().startsWith("import ") || it.trim().startsWith("package ") }
        val code = lines.filterNot { it.trim().startsWith("import ") || it.trim().startsWith("package ") }.joinToString("\n").trim('\n')
        return imp.map { it.trim() } to code
    }
    val (oldImp, oldCode) = split(old); val (newImp, newCode) = split(new)
    if (oldImp.isEmpty() && newImp.isEmpty() || oldCode.isBlank()) return null
    val withCode = when {
        text.contains(oldCode) -> text.replaceFirst(oldCode, newCode)
        else -> replaceIgnoringIndent(text, oldCode, newCode) ?: return null
    }
    // Imports: drop those the edit removed, add those it introduced.
    val gone = oldImp.filter { it.startsWith("import ") && it !in newImp }.toSet()
    var out = withCode.lines().filterNot { it.trim() in gone }.joinToString("\n")
    newImp.filter { it.startsWith("import ") && out.lines().none { l -> l.trim() == it } }.forEach { out = addImport(out, it.removePrefix("import ").trim()) }
    return out
}

internal fun importEdit(text: String, old: String, new: String): String? {
    fun lines(s: String) = s.lines().map { it.trim() }.filter { it.isNotEmpty() }
    // The same `package` line on both sides is just context: drop it.
    val pkgOld = lines(old).filter { it.startsWith("package ") }; val pkgNew = lines(new).filter { it.startsWith("package ") }
    if (pkgOld != pkgNew) return null
    fun imports(s: String) = lines(s).filter { !it.startsWith("package ") }
    val gone = imports(old); val added = imports(new)
    if (gone.isEmpty() || gone.any { !it.startsWith("import ") } || added.any { !it.startsWith("import ") }) return null
    val lines = text.split("\n")
    if (gone.none { g -> lines.any { it.trim() == g } }) return null
    val kept = lines.filter { it.trim() !in gone || it.trim() in added }
    val firstImport = kept.indexOfFirst { it.startsWith("import ") }
    val toAdd = added.filter { a -> kept.none { it.trim() == a } }
    val at = if (firstImport >= 0) firstImport else (kept.indexOfFirst { it.startsWith("package ") } + 1).coerceAtLeast(0)
    return (kept.subList(0, at) + toAdd + kept.subList(at, kept.size)).joinToString("\n")
}
