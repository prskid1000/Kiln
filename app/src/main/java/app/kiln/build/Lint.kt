package app.kiln.build

/**
 * Mistakes the Kotlin compiler accepts but that ship visible bugs — each rule came from an app an
 * agent built and "verified" without noticing. Reported with every check/build: warnings, and errors
 * (which fail the build) for mistakes that make part of the screen unusable.
 */
object Lint {
    private val template = Regex("""(?<![\\$])\$([a-zA-Z_][a-zA-Z0-9_]*)\.([a-zA-Z_][a-zA-Z0-9_]*)""")
    private val stringLit = Regex(""""(?:[^"\\\n]|\\.)*"""")

    /** Index just past the `}` closing a `${` template whose code starts at [from]; nested strings and braces skipped. */
    private fun templateEnd(text: String, from: Int): Int {
        var e = from; var depth = 1
        while (e < text.length && depth > 0) {
            when (text[e]) {
                '{' -> depth++
                '}' -> depth--
                '"' -> { e++; while (e < text.length && text[e] != '"' && text[e] != '\n') e += if (text[e] == '\\') 2 else 1 }
                '\n' -> return e
            }
            e++
        }
        return e
    }

    /** [text] with comments and string/char literal contents replaced by spaces (newlines kept, offsets unchanged). */
    internal fun codeOnly(text: String): String {
        val b = StringBuilder(text)
        var i = 0
        fun blank(from: Int, to: Int) { for (k in from until minOf(to, b.length)) if (b[k] != '\n') b[k] = ' ' }
        while (i < text.length) {
            when {
                text.startsWith("//", i) -> { val e = text.indexOf('\n', i).let { if (it < 0) text.length else it }; blank(i, e); i = e }
                text.startsWith("/*", i) -> { val e = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }; blank(i, e); i = e }
                text.startsWith("\"\"\"", i) -> { val e = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }; blank(i + 3, e - 3); i = e }
                text[i] == '"' || text[i] == '\'' -> {
                    val q = text[i]; var e = i + 1
                    while (e < text.length && text[e] != q && text[e] != '\n') e = when {
                        text[e] == '\\' -> e + 2
                        // A template's code may hold its own strings ("${if (open) "{" else ""}"): skip to its closing brace.
                        q == '"' && text.startsWith("\${", e) -> templateEnd(text, e + 2)
                        else -> e + 1
                    }
                    blank(i + 1, e); i = minOf(e + 1, text.length)
                }
                else -> i++
            }
        }
        return b.toString()
    }

    // Only compiled sources: a sample .kt in assets/ must not fail the build.
    fun run(project: Project): List<Diagnostic> = project.files()
        .filter { it.extension == "kt" && it.path.startsWith(java.io.File(project.dir, "src").path + java.io.File.separator) }.flatMap { f ->
        val rel = project.rel(f)
        val lines = f.readLines()
        val out = mutableListOf<Diagnostic>()
        lines.forEachIndexed { i, line ->
            // "Total: $items.size" prints the whole list and then ".size": the template ends at the name.
            for (lit in stringLit.findAll(line)) for (m in template.findAll(lit.value)) {
                val (name, member) = m.destructured
                if (member in setOf("com", "org", "net", "io", "txt", "png", "jpg", "json", "kt", "html")) continue
                out += Diagnostic("warning", "\"\$$name.$member\" inserts $name and then the text \".$member\" — write \"\${$name.$member}\"",
                    rel, i + 1, lit.range.first + m.range.first + 1, "kiln-lint", line.trim())
            }
        }
        // An icon-only button with no description is invisible to TalkBack and to tap-by-label.
        val text = lines.joinToString("\n")
        // A lazy list inside a verticalScroll column throws "measured with an infinity maximum height" at runtime.
        Regex("""verticalScroll\s*\(""").findAll(text).forEach { m ->
            val after = text.substring(m.range.first, minOf(text.length, m.range.first + 2500))
            // The scrolling Column's body: up to its matching close brace (rough but cheap).
            var depth = 0; var started = false; var end = after.length
            for ((i, c) in after.withIndex()) {
                if (c == '{') { depth++; started = true } else if (c == '}') { depth--; if (started && depth == 0) { end = i; break } }
            }
            val lazy = Regex("""\b(LazyColumn|LazyRow|LazyVerticalGrid|LazyVerticalStaggeredGrid|KGrid)\s*[({]""").find(after.substring(0, end)) ?: return@forEach
            val line = text.substring(0, m.range.first + lazy.range.first).count { it == '\n' } + 1
            out += Diagnostic("warning", "${lazy.groupValues[1]} inside a verticalScroll Column crashes at runtime (\"measured with an infinity maximum height\"): use one LazyColumn for the screen, or give the inner list a fixed height",
                rel, line, null, "kiln-lint", lines.getOrElse(line - 1) { "" }.trim())
        }
        // KilnScreen hands its content the space the top bar takes; content that ignores it draws under the
        // bar, hidden and untappable (run 11: a search bar and the first expense behind the title).
        // This rule fails the build, so it reads only code: comments and string contents are blanked (same
        // offsets, so line numbers hold) — a commented-out KilnScreen or a "}" in a Text must not count.
        val code = codeOnly(text)
        Regex("""\bKilnScreen\s*\(""").findAll(code).forEach { m ->
            val call = code.substring(m.range.first)
            // The content lambda: the trailing one after the argument list, or content = { … }.
            var depth = 0; var close = -1
            for ((i, c) in call.withIndex()) { if (c == '(') depth++ else if (c == ')') { depth--; if (depth == 0) { close = i; break } } }
            if (close < 0) return@forEach
            val trailing = Regex("""^\s*\{""").find(call.substring(close + 1))?.let { close + 1 + it.range.last }
            val named = Regex("""\bcontent\s*=\s*\{""").find(call.substring(0, close))?.range?.last
            val open = trailing ?: named ?: return@forEach
            var d = 0; var end = call.length
            for (i in open until call.length) { if (call[i] == '{') d++ else if (call[i] == '}') { d--; if (d == 0) { end = i; break } } }
            val body = call.substring(open + 1, end)
            // `{ pad -> …}` or typed `{ pad: PaddingValues -> …}`.
            val param = Regex("""^\s*(\w+)\s*(?::\s*[\w.<>?]+\s*)?->""").find(body)?.groupValues?.get(1)
            val rest = if (param != null) body.substringAfter("->") else body
            val used = Regex("""\b${Regex.escape(param ?: "it")}\b""").containsMatchIn(rest)
            if (param == "_" || !used) {
                val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                out += Diagnostic("error", "KilnScreen's content ignores its padding, so it draws under the top bar — hidden and untappable. " +
                    "Use it: `KilnScreen(\"Title\") { pad -> Column(Modifier.screenPadding(pad)) { … } }` (or `modifier = Modifier.padding(pad)` on a list or KCrudList).",
                    rel, line, null, "kiln-lint", lines.getOrElse(line - 1) { "" }.trim())
            }
        }
        // A list key made from field hashes collides for two equal-looking items and crashes the list
        // ("Key … was already used") — run 13's Home crashed on every launch this way.
        Regex("""\bkey\s*=\s*\{[^}\n]*hashCode\(\)""").findAll(text).forEach { m ->
            val line = text.substring(0, m.range.first).count { it == '\n' } + 1
            out += Diagnostic("warning", "a list key built from hashCode() repeats for two items with the same values and crashes the list (\"Key … was already used\"): " +
                "use a unique id — KCollection rows have one: items(rows, key = { it.id }) { row -> … }",
                rel, line, null, "kiln-lint", lines.getOrElse(line - 1) { "" }.trim())
        }
        Regex("""IconButton\s*\(""").findAll(text).forEach { m ->
            val body = text.substring(m.range.first, minOf(text.length, m.range.first + 400))
            val icon = Regex("""Icon\s*\(([^)]*)\)""").find(body) ?: return@forEach
            if (Regex("""contentDescription\s*=\s*null""").containsMatchIn(icon.value) ||
                (!icon.value.contains("contentDescription") && Regex(""",\s*null\s*[,)]""").containsMatchIn(icon.value + ")"))) {
                val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                out += Diagnostic("warning", "icon-only button without a contentDescription: TalkBack can't read it and tap can't find it — describe the action (e.g. \"Delete milk\")",
                    rel, line, null, "kiln-lint", lines[line - 1].trim())
            }
        }
        out
    }
}
