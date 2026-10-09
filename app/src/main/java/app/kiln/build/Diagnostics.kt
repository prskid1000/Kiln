package app.kiln.build

import kotlinx.serialization.Serializable

/** One compiler/linker message, in the shape the agent gets back. */
@Serializable
data class Diagnostic(
    val severity: String,         // error | warning | info
    val message: String,
    val file: String? = null,     // project-relative when inside the project
    val line: Int? = null,
    val col: Int? = null,
    val tool: String,
    val source: String? = null,   // the offending source line, when known
)

/**
 * Turns raw tool output into [Diagnostic]s. Unparsed error output is still
 * returned (as one diagnostic) — a failure must never come back empty.
 */
object Diagnostics {
    // kotlinc 2.4: "data/user/0/…/Main.kt:12:5: error: unresolved reference 'foo'."
    // (paths relative to the tool server's cwd, which is /) — older
    // releases: "e: file:///abs/Main.kt:12:5 Unresolved reference 'foo'." Both parse.
    private val kotlinc = Regex("""^(?:([ewi]): )?(?:file://)?(/?[^:\s/][^:]*?|/[^:]+?):(\d+):(\d+):? (?:(error|warning|info): )?(.*)$""")
    private val kotlincNoPos = Regex("""^([ewi]): (.*)$""")
    // javac: "/abs/R.java:12: error: message"
    private val javac = Regex("""^(/[^:]+?):(\d+): (error|warning): (.*)$""")
    // aapt2: "/abs/res/values/strings.xml:4: error: message" or "error: message"
    private val aapt2 = Regex("""^(/[^:]+?)(?::(\d+))?(?::(\d+))?: (error|warn|warning|note): (.*)$""")
    private val d8 = Regex("""^(Error|Warning)(?: in ([^:]+))?:\s*(.*)$""")

    fun parse(tool: String, output: String, project: Project?, failed: Boolean): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        val lines = output.lines()
        for ((li, raw) in lines.withIndex()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            val d = when (tool) {
                "kotlinc" -> kotlinc.matchEntire(line)?.let { m ->
                    val severity = m.groupValues[5].ifBlank { sev(m.groupValues[1]) }
                    val path = m.groupValues[2].let { if (it.startsWith("/")) it else "/$it" }
                    Diagnostic(severity, m.groupValues[6], path, m.groupValues[3].toInt(),
                        m.groupValues[4].toInt(), tool)
                } ?: kotlincNoPos.matchEntire(line)?.let { m -> Diagnostic(sev(m.groupValues[1]), m.groupValues[2], tool = tool) }
                "javac" -> javac.matchEntire(line)?.let { m ->
                    Diagnostic(m.groupValues[3], m.groupValues[4], m.groupValues[1], m.groupValues[2].toInt(), tool = tool)
                }
                "aapt2" -> aapt2.matchEntire(line)?.let { m ->
                    Diagnostic(if (m.groupValues[4].startsWith("warn")) "warning" else if (m.groupValues[4] == "note") "info" else "error",
                        m.groupValues[5], m.groupValues[1], m.groupValues[2].toIntOrNull(), m.groupValues[3].toIntOrNull(), tool)
                } ?: if (line.startsWith("error:")) Diagnostic("error", line.removePrefix("error:").trim(), tool = tool) else null
                "d8" -> d8.matchEntire(line)?.let { m ->
                    // d8 prints "Error in <origin>:" and the message on the next line.
                    val msg = m.groupValues[3].ifBlank { lines.drop(li + 1).firstOrNull { it.isNotBlank() }?.trim().orEmpty() }
                    Diagnostic(if (m.groupValues[1] == "Error") "error" else "warning", msg, m.groupValues[2].ifBlank { null }, tool = tool)
                }
                else -> null
            }
            if (d != null) out += d
        }
        val withSource = out.map { d -> withRelAndSource(d, project) }
        if (failed && withSource.none { it.severity == "error" }) {
            val tail = lines.filter { it.isNotBlank() }.takeLast(30).joinToString("\n")
            return withSource + Diagnostic("error", tail.ifBlank { "$tool failed with no output" }, tool = tool)
        }
        return withSource
    }

    private fun sev(c: String) = when (c) { "e" -> "error"; "w" -> "warning"; else -> "info" }

    private fun withRelAndSource(d: Diagnostic, project: Project?): Diagnostic {
        if (project == null || d.file == null) return d
        val f = java.io.File(d.file)
        val root = project.dir.canonicalPath
        // root + separator: a sibling project "notes2" isn't inside "notes".
        if (!f.canonicalPath.startsWith(root + java.io.File.separator)) return d
        val src = d.line?.let { n -> runCatching { f.readLines().getOrNull(n - 1)?.trim() }.getOrNull() }
        return d.copy(file = project.rel(f), source = src)
    }
}
