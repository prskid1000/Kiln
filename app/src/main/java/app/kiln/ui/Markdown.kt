package app.kiln.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.kiln.ui.theme.N
import app.kiln.ui.theme.T
import app.kiln.ui.theme.vInset

/**
 * The Markdown models actually write in chat: headings, bullet/numbered lists,
 * fenced code, and inline **bold**, *italic*, `code`. Anything else is text.
 */
private sealed interface Block {
    data class Para(val text: String) : Block
    data class Heading(val text: String) : Block
    data class Item(val marker: String, val text: String, val indent: Int) : Block
    data class Code(val text: String) : Block
}

private fun parse(md: String): List<Block> {
    val out = mutableListOf<Block>()
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += Block.Para(para.toString().trim()); para.clear() }
    val lines = md.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trimStart()
        when {
            t.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code.appendLine(lines[i]); i++ }
                out += Block.Code(code.toString().trimEnd())
            }
            Regex("^#{1,6} ").containsMatchIn(t) -> { flush(); out += Block.Heading(t.trimStart('#').trim()) }   // not "#1 priority"
            Regex("^([-*•]|\\d+[.)])\\s+").containsMatchIn(t) -> {
                flush()
                val m = Regex("^([-*•]|\\d+[.)])\\s+").find(t)!!
                out += Block.Item(if (m.groupValues[1].first().isDigit()) m.groupValues[1] else "•", t.substring(m.range.last + 1),
                    (line.length - t.length) / 2)
            }
            t.isBlank() -> flush()
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line.trim()) }
        }
        i++
    }
    flush()
    return out
}

private val inline = Regex("""\*\*(.+?)\*\*|`([^`]+)`|(?<![*\w])\*(?!\s)(.+?)(?<!\s)\*(?!\w)""")

fun inlineMarkdown(s: String): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (m in inline.findAll(s)) {
        append(s.substring(at, m.range.first))
        when {
            m.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(m.groupValues[1]) }
            m.groups[2] != null -> withStyle(SpanStyle(fontFamily = T.mono.fontFamily, color = N.accent2, background = N.accent800.copy(alpha = 0.45f))) {
                append(m.groupValues[2])
            }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groupValues[3]) }
        }
        at = m.range.last + 1
    }
    append(s.substring(at))
}

@Composable
fun Markdown(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parse(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Block.Para -> Text(inlineMarkdown(b.text), style = T.body)
                is Block.Heading -> Text(inlineMarkdown(b.text), style = T.subtitle, modifier = Modifier.padding(top = 4.dp))
                is Block.Item -> Row(Modifier.padding(start = (b.indent * 14).dp)) {
                    Text(b.marker, style = T.body.copy(color = N.accent2), modifier = Modifier.width(if (b.marker == "•") 16.dp else 22.dp))
                    Text(inlineMarkdown(b.text), style = T.body)
                }
                is Block.Code -> Text(b.text, style = T.mono.copy(color = N.text), softWrap = false,
                    modifier = Modifier.fillMaxWidth().vInset().horizontalScroll(rememberScrollState()).padding(12.dp))
            }
        }
    }
}
