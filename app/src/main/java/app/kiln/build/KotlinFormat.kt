package app.kiln.build

/**
 * A small, safe Kotlin formatter applied whenever a .kt file is saved (by the agent or in the editor):
 * re-indents by bracket depth (4 spaces), continuation lines +4, KDoc aligned, imports sorted and
 * de-duplicated, trailing spaces trimmed, runs of blank lines collapsed, one final newline.
 *
 * Indentation can't change what Kotlin means, except inside multi-line raw strings — their lines are
 * left exactly as written. A file whose brackets don't balance (mid-edit, or a construct this doesn't
 * understand) is returned unchanged rather than risk mangling it.
 */
object KotlinFormat {
    private const val INDENT = "    "

    fun format(source: String): String {
        // Tabs are left alone: inside strings they're content, and re-indenting replaces leading ones anyway.
        val text = source.replace("\r\n", "\n")
        val lines = text.split("\n")
        val out = ArrayList<String>(lines.size)
        val rawLines = HashSet<Int>()             // indexes in out that belong to a raw string: never changed
        var depth = 0                 // open ( [ { at the start of the current line
        var inBlock = false           // inside /* */
        var inRaw = false             // inside """ … """
        var continuation = false      // the previous code line asked for a continuation indent
        for (raw in lines) {
            val startedInRaw = inRaw
            val startedInBlock = inBlock
            val trimmed = raw.trim()
            // Scan the line: track strings, comments and bracket depth.
            var opens = 0; var closes = 0; var leadingCloses = 0; var seenCode = false
            var i = 0; var inStr = false; var inChar = false
            val templates = ArrayDeque<Int>()     // brace depth where a ${ … } template started, inside "…"
            var lineComment = false
            while (i < raw.length) {
                val c = raw[i]
                val next = raw.getOrNull(i + 1)
                when {
                    lineComment -> break
                    inBlock -> { if (c == '*' && next == '/') { inBlock = false; i++ } }
                    inRaw -> { if (raw.startsWith("\"\"\"", i)) { inRaw = false; i += 2 } }
                    inStr -> when {
                        c == '\\' -> i++
                        c == '$' && next == '{' -> { templates.addLast(0); inStr = false; i++ }
                        c == '"' -> inStr = false
                    }
                    inChar -> when (c) { '\\' -> i++; '\'' -> inChar = false }
                    c == '/' && next == '/' -> lineComment = true
                    c == '/' && next == '*' -> { inBlock = true; i++ }
                    raw.startsWith("\"\"\"", i) -> { inRaw = true; i += 2; seenCode = true }
                    c == '"' -> { inStr = true; seenCode = true }
                    c == '\'' -> { inChar = true; seenCode = true }
                    c == '{' || c == '(' || c == '[' -> {
                        if (c == '{' && templates.isNotEmpty()) templates.addLast(templates.removeLast() + 1)
                        opens++; seenCode = true
                    }
                    c == '}' || c == ')' || c == ']' -> {
                        if (c == '}' && templates.isNotEmpty()) {
                            val d = templates.removeLast()
                            if (d == 0) { inStr = true; i++; continue }   // end of ${ … }: back in the string
                            templates.addLast(d - 1)
                        }
                        if (!seenCode) leadingCloses++ else closes++
                    }
                    !c.isWhitespace() -> seenCode = true
                }
                i++
            }
            when {
                // A raw string's own lines, and blank lines, are kept as they are (blank → empty).
                startedInRaw -> { rawLines += out.size; out += raw }
                trimmed.isEmpty() -> out += ""
                startedInBlock -> out += INDENT.repeat(maxOf(0, depth)) + (if (trimmed.startsWith("*")) " " else "") + trimmed
                else -> {
                    val level = maxOf(0, depth - leadingCloses)
                    val cont = continuation || CONT_START.any { trimmed.startsWith(it) }
                    // A line that opens a raw string keeps its end: trailing spaces there are the string's content.
                    out += INDENT.repeat(level) + (if (cont && leadingCloses == 0) INDENT else "") + (if (inRaw) raw.trimStart() else trimmed)
                }
            }
            depth += opens - closes - leadingCloses
            if (depth < 0) return source                     // more closes than opens: don't touch it
            // Comment lines (and a trailing /* … */) never ask for a continuation: "*/" isn't a "/" operator.
            if (!startedInRaw && !startedInBlock && trimmed.isNotEmpty() && !trimmed.startsWith("//") &&
                !trimmed.startsWith("/*") && !trimmed.startsWith("*")) {
                val code = trimmed.substringBefore(" //").replace(Regex("""/\*.*?\*/"""), "").trimEnd()
                // `import a.b.*` ends in "*" but is a whole statement, not a multiplication.
                val statement = code.startsWith("import ") || code.startsWith("package ")
                // `n++` / `i--` end a statement; they aren't a dangling + or -.
                continuation = !statement && opens <= closes + leadingCloses && CONT_END.any { code.endsWith(it) } && !code.endsWith("==") &&
                    !code.endsWith("++") && !code.endsWith("--")
            }
        }
        if (depth != 0 || inRaw || inBlock) return source  // unbalanced: leave it for the compiler to report
        return tidy(out, rawLines)
    }

    private val CONT_START = listOf(".", "?.", "?:", "&&", "||")
    private val CONT_END = listOf("=", "->", "&&", "||", "+", "-", "*", "/", "?:")

    /** Sorted, de-duplicated imports; at most one blank line in a row; one final newline. */
    private fun tidy(lines: List<String>, rawLines: Set<Int> = emptySet()): String {
        // Lines inside a raw string are content, never imports (a raw "import b" must not be sorted away).
        val firstImport = lines.indices.firstOrNull { it !in rawLines && lines[it].startsWith("import ") } ?: -1
        val result = ArrayList<String>(lines.size)
        var shift = 0                       // how much the import block shrank (lines after it moved up by this)
        if (firstImport >= 0) {
            var lastImport = firstImport
            for (k in firstImport until lines.size) {
                val l = lines[k]
                if (k in rawLines) break
                if (l.startsWith("import ")) lastImport = k else if (l.isNotBlank()) break
            }
            val imports = lines.subList(firstImport, lastImport + 1).filter { it.startsWith("import ") }.distinct().sorted()
            result += lines.subList(0, firstImport); result += imports; result += lines.subList(lastImport + 1, lines.size)
            shift = (lastImport + 1 - firstImport) - imports.size
        } else result += lines
        val collapsed = ArrayList<String>(result.size)
        var prevRaw = false
        for ((k, l) in result.withIndex()) {
            val raw = (k + shift) in rawLines
            // Inside a raw string blank lines are content; elsewhere at most one in a row.
            if (raw || prevRaw || !(l.isEmpty() && collapsed.lastOrNull()?.isEmpty() == true)) collapsed += l
            prevRaw = raw
        }
        while (collapsed.lastOrNull()?.isEmpty() == true) collapsed.removeAt(collapsed.lastIndex)
        return collapsed.joinToString("\n") + "\n"
    }
}
