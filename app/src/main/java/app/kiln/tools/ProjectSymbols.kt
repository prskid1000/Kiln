package app.kiln.tools

import app.kiln.build.Project

/**
 * Everything the model has declared in the project — functions, classes, objects and their properties — with
 * the signature it wrote, so a build error about one of them can say what actually exists: "Did you mean
 * fun formatMoney(amount: Double, currency: String): String (ui/Format.kt)?", "Repo has: expenses, settings,
 * fun monthTotal(): Double". Read from the sources each time (a project is a handful of files).
 */
class ProjectSymbols private constructor(val all: List<Symbol>) {

    /** [owner] is the enclosing class/object (or an extension's receiver type), null at top level. */
    data class Symbol(val name: String, val owner: String?, val signature: String, val file: String)

    fun named(name: String): List<Symbol> = all.filter { it.name == name }

    /** What [owner] has: its members and the extensions written for it. */
    fun members(owner: String): List<Symbol> = all.filter { it.owner == owner }

    fun isType(name: String) = all.any { it.name == name && Regex("""\b(class|object|interface)\s""").containsMatchIn(it.signature) }

    /** The closest names to [name] among [among] (spelling and shared words), best first. */
    fun closest(name: String, among: List<Symbol> = all, limit: Int = 3): List<Symbol> {
        val low = name.lowercase()
        val words = Regex("""[A-Z][a-z0-9]+|[a-z0-9]+""").findAll(name).map { it.value.lowercase() }.filter { it.length >= 3 }.toList()
        return among.filter { it.name != name }.map { s ->
            val d = NameCheck.distance(low, s.name.lowercase())
            val shared = words.count { w -> s.name.lowercase().contains(w) }
            s to d - shared * 3
        }.filter { it.second <= maxOf(2, name.length / 2) }.sortedBy { it.second }.distinctBy { it.first.name to it.first.owner }.take(limit).map { it.first }
    }

    companion object {
        private val TYPE = Regex("""\b(?:(?:data|sealed|enum|abstract|open|private|internal|inner|value)\s+)*(class|object|interface)\s+(\w+)""")
        private val FUN = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:([A-Z][\w.]*?(?:<[^>]*>)?)\.)?(\w+)\s*\(""")
        private val PROP = Regex("""\b(val|var)\s+(\w+)\s*(:\s*[^=\n{]+)?""")

        fun of(project: Project): ProjectSymbols {
            val out = mutableListOf<Symbol>()
            for (f in runCatching { project.files().filter { it.extension == "kt" } }.getOrDefault(emptyList())) {
                val rel = project.rel(f)
                val text = runCatching { f.readText() }.getOrNull() ?: continue
                out += parse(NameCheck.codeOnly(text), text, rel)
            }
            return ProjectSymbols(out)
        }

        /** [code] has comments and strings blanked (same offsets as [text]); signatures are cut from [text]. */
        internal fun parse(code: String, text: String, file: String): List<Symbol> {
            // Brace depth before each character, and each type's body range.
            val depth = IntArray(code.length + 1)
            var d = 0
            for (i in code.indices) { depth[i] = d; when (code[i]) { '{' -> d++; '}' -> d = maxOf(0, d - 1) } }
            depth[code.length] = d
            data class Body(val name: String, val from: Int, val to: Int, val inner: Int)
            val bodies = mutableListOf<Body>()
            val out = mutableListOf<Symbol>()
            fun ownerAt(i: Int): Body? = bodies.filter { i in it.from until it.to }.maxByOrNull { it.from }
            fun cut(from: Int, to: Int) = text.substring(from, to.coerceAtMost(text.length)).replace(Regex("""\s+"""), " ").trim().let {
                if (it.length > 200) it.take(200) + "…" else it }

            for (m in TYPE.findAll(code)) {
                val name = m.groupValues[2]
                // Its header: up to the body's "{" (or the line's end for a body-less class).
                var i = m.range.last + 1; var paren = 0
                while (i < code.length) { val c = code[i]; if (c == '(') paren++ else if (c == ')') paren-- else if (paren == 0 && (c == '{' || c == '\n')) break; i++ }
                out += Symbol(name, ownerAt(m.range.first)?.name, cut(m.range.first, i), file)
                // Constructor properties belong to the type.
                val header = code.substring(m.range.last + 1, i)
                PROP.findAll(header).forEach { p -> out += Symbol(p.groupValues[2], name, cut(m.range.last + 1 + p.range.first, m.range.last + 1 + p.range.last + 1).trimEnd(',', ')'), file) }
                if (i < code.length && code[i] == '{') {
                    var j = i + 1; var k = 1
                    while (j < code.length && k > 0) { if (code[j] == '{') k++ else if (code[j] == '}') k--; j++ }
                    bodies += Body(name, i + 1, j, depth[i] + 1)
                }
            }
            for (m in FUN.findAll(code)) {
                val owner = ownerAt(m.range.first)
                // Only top-level functions and direct members (not functions local to another function).
                if (depth[m.range.first] != (owner?.inner ?: 0)) continue
                var i = m.range.last + 1; var paren = 1
                while (i < code.length && paren > 0) { if (code[i] == '(') paren++ else if (code[i] == ')') paren--; i++ }
                // The return type, if written: up to "{", "=" or the line's end.
                var e = i
                while (e < code.length && code[e] != '{' && code[e] != '=' && code[e] != '\n') e++
                val receiver = m.groupValues[1].substringBefore('<').ifEmpty { null }
                out += Symbol(m.groupValues[2], receiver ?: owner?.name, cut(m.range.first, e), file)
            }
            for (m in PROP.findAll(code)) {
                val owner = ownerAt(m.range.first)
                if (depth[m.range.first] != (owner?.inner ?: 0)) continue
                // Constructor parameters are inside a header's parentheses: already taken above.
                val before = code.substring(0, m.range.first)
                if (before.count { it == '(' } > before.count { it == ')' }) continue
                out += Symbol(m.groupValues[2], owner?.name, cut(m.range.first, m.range.last + 1), file)
            }
            return out
        }
    }
}
