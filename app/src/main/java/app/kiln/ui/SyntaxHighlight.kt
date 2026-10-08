package app.kiln.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import app.kiln.ui.theme.N

/**
 * Colours source code in the Files editor without changing it (a VisualTransformation, so editing,
 * cursor and save work exactly as before). Kotlin, XML and JSON; anything else, or a very large
 * file, is shown plain.
 */
class SyntaxHighlight(private val language: Language) : VisualTransformation {
    enum class Language { Kotlin, Xml, Json, Plain }

    companion object {
        fun forPath(path: String) = SyntaxHighlight(when (path.substringAfterLast('.').lowercase()) {
            "kt", "kts", "java" -> Language.Kotlin
            "xml" -> Language.Xml
            "json" -> Language.Json
            else -> Language.Plain
        })

        private val keyword = SpanStyle(color = N.accent)
        private val string = SpanStyle(color = N.ok)
        private val number = SpanStyle(color = N.warn)
        private val comment = SpanStyle(color = N.textMuted, fontStyle = FontStyle.Italic)
        private val annotation = SpanStyle(color = N.warn)
        private val type = SpanStyle(color = N.accent2)
        private val kit = SpanStyle(color = Color(0xFF8FD3E8))      // K… kit names stand out
        private val tag = SpanStyle(color = N.accent)
        private val attr = SpanStyle(color = N.accent2)

        private val KOTLIN_KEYWORDS = setOf(
            "package", "import", "class", "interface", "object", "fun", "val", "var", "if", "else", "when", "for", "while", "do",
            "return", "break", "continue", "try", "catch", "finally", "throw", "is", "in", "as", "null", "true", "false", "this",
            "super", "private", "public", "internal", "protected", "override", "open", "abstract", "data", "sealed", "enum",
            "companion", "suspend", "inline", "reified", "operator", "lateinit", "const", "typealias", "by", "get", "set", "init",
            "constructor", "out", "vararg", "crossinline", "noinline", "annotation", "value", "where",
        )
        private val KOTLIN = Regex(
            """(//[^\n]*)|(/\*[\s\S]*?\*/)|("""+"\"\"\""+"""[\s\S]*?"""+"\"\"\""+""")|("(?:[^"\\\n]|\\.)*")|('(?:[^'\\\n]|\\.)')""" +
            """|(@[A-Za-z_][\w.]*)|(\b\d[\d_]*(?:\.\d+)?[fFLuU]?\b|\b0x[0-9a-fA-F_]+\b)|(\b[A-Za-z_]\w*\b)""")
        private val XML = Regex("""(<!--[\s\S]*?-->)|(</?[\w:.-]+)|(\b[\w:.-]+(?==))|("[^"]*")|(/?>)""")
        private val JSON = Regex("""("(?:[^"\\]|\\.)*"\s*(?=:))|("(?:[^"\\]|\\.)*")|(\b-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b)|(\btrue\b|\bfalse\b|\bnull\b)""")
    }

    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        if (language == Language.Plain || s.length > 200_000) return TransformedText(text, OffsetMapping.Identity)
        val b = AnnotatedString.Builder(s)
        fun span(style: SpanStyle, r: IntRange) = b.addStyle(style, r.first, r.last + 1)
        when (language) {
            Language.Kotlin -> for (m in KOTLIN.findAll(s)) {
                val g = m.groups
                when {
                    g[1] != null || g[2] != null -> span(comment, m.range)
                    g[3] != null || g[4] != null || g[5] != null -> span(string, m.range)
                    g[6] != null -> span(annotation, m.range)
                    g[7] != null -> span(number, m.range)
                    g[8] != null -> {
                        val w = m.value
                        when {
                            w in KOTLIN_KEYWORDS -> span(keyword, m.range)
                            w.length > 1 && w[0] == 'K' && w[1].isUpperCase() || w == "Nocturne" -> span(kit, m.range)
                            w[0].isUpperCase() -> span(type, m.range)
                        }
                    }
                }
            }
            Language.Xml -> for (m in XML.findAll(s)) {
                val g = m.groups
                when {
                    g[1] != null -> span(comment, m.range)
                    g[2] != null || g[5] != null -> span(tag, m.range)
                    g[3] != null -> span(attr, m.range)
                    g[4] != null -> span(string, m.range)
                }
            }
            Language.Json -> for (m in JSON.findAll(s)) {
                val g = m.groups
                when {
                    g[1] != null -> span(attr, m.range)
                    g[2] != null -> span(string, m.range)
                    g[3] != null -> span(number, m.range)
                    g[4] != null -> span(keyword, m.range)
                }
            }
            Language.Plain -> {}
        }
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }
}
