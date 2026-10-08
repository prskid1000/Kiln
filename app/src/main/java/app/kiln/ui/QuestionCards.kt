package app.kiln.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kiln.agent.ANSWER_SEP
import app.kiln.agent.Question
import app.kiln.tools.AskQ
import app.kiln.tools.Looks
import app.kiln.ui.theme.N
import app.kiln.ui.theme.T
import app.kiln.ui.theme.vCard
import app.kiln.ui.theme.vInset

/**
 * The agent's questions, Claude Code style: per question a header chip, the question, option rows
 * (label + description, a check or a tick box for multi-select), the focused option's preview, and
 * "Other" for an own answer. One Submit sends every answer.
 */
@Composable
fun QuestionCard(q: Question, onAnswer: (String) -> Unit) {
    val qs = q.questions.ifEmpty { listOf(AskQ(q.text, "", q.options.map { app.kiln.tools.AskOption(it) })) }
    val picked = remember(q) { qs.map { mutableStateListOf<String>() } }
    val other = remember(q) { qs.map { mutableStateOf("") } }
    fun answerOf(i: Int) = (picked[i] + listOfNotNull(other[i].value.trim().takeIf { it.isNotEmpty() })).joinToString(", ")
    val ready = qs.indices.all { answerOf(it).isNotBlank() }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().heightIn(max = 560.dp)
        .vCard(N.shapeLg, N.accent.copy(alpha = 0.6f)).verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        qs.forEachIndexed { i, item ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.header.isNotBlank()) Text(item.header.uppercase(), style = T.kicker,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(N.accent.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp))
                Text(item.question, style = T.subtitle)
                if (item.multiSelect) Text("Choose any", style = T.label)
                item.options.forEach { o ->
                    val on = o.label in picked[i]
                    Row(Modifier.fillMaxWidth().clip(N.shapeMd).background(if (on) N.accent.copy(alpha = 0.14f) else Color.Transparent)
                        .border(1.dp, if (on) N.accent else N.cardRing, N.shapeMd)
                        .clickable(role = if (item.multiSelect) Role.Checkbox else Role.RadioButton) {
                            if (item.multiSelect) { if (on) picked[i].remove(o.label) else picked[i].add(o.label) }
                            else { picked[i].clear(); picked[i].add(o.label); other[i].value = "" }
                        }.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 2.dp).size(18.dp).clip(if (item.multiSelect) RoundedCornerShape(4.dp) else CircleShape)
                            .background(if (on) N.accent else Color.Transparent).border(1.5.dp, if (on) N.accent else N.textMuted,
                                if (item.multiSelect) RoundedCornerShape(4.dp) else CircleShape), contentAlignment = Alignment.Center) {
                            if (on) Icon(Icons.Rounded.Check, null, tint = N.bg, modifier = Modifier.size(14.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(o.label, style = T.control)
                            if (o.description.isNotBlank()) Text(o.description, style = T.bodySmall)
                        }
                    }
                }
                // The focused option's preview (mockups, code) — as in Claude Code's side-by-side preview.
                val preview = item.options.firstOrNull { it.label in picked[i] && !it.preview.isNullOrBlank() }?.preview
                if (preview != null && !item.multiSelect) Text(preview, style = T.mono,
                    modifier = Modifier.fillMaxWidth().vInset().horizontalScroll(rememberScrollState()).padding(10.dp))
                KField("", other[i].value, { v -> other[i].value = v; if (v.isNotBlank() && !item.multiSelect) picked[i].clear() },
                    Modifier.fillMaxWidth().semantics { contentDescription = "Other answer" }, hint = "Other…")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (qs.size > 1) "${qs.indices.count { answerOf(it).isNotBlank() }}/${qs.size} answered" else "", style = T.label, modifier = Modifier.weight(1f))
            KButton("Submit", Tone.Accent, enabled = ready) { onAnswer(qs.indices.joinToString(ANSWER_SEP) { answerOf(it) }) }
        }
    }
}

/** The agent's "pick a look" card: a swatch per kit theme, the surface styles, or Kiln's default / own words. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LookPicker(title: String, onAnswer: (String) -> Unit) {
    var theme by remember { mutableStateOf(Looks.themes.first().name) }
    var style by remember { mutableStateOf("Flat") }
    var free by remember { mutableStateOf("") }
    val chosen = Looks.themes.first { it.name == theme }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().vCard(N.shapeLg, N.accent.copy(alpha = 0.6f)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("LOOK", style = T.kicker)
        Text(title, style = T.subtitle)
        Text("Theme · ${chosen.mood}", style = T.label)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(Looks.themes.size) { i ->
                val t = Looks.themes[i]
                val on = t.name == theme
                Column(Modifier.width(72.dp).clip(RoundedCornerShape(12.dp)).clickable { theme = t.name }.padding(4.dp)
                    .semantics { contentDescription = t.name }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(Color(t.bg))
                        .border(if (on) 3.dp else 1.dp, if (on) N.accent else N.cardRing, CircleShape), contentAlignment = Alignment.Center) {
                        Row { Box(Modifier.size(16.dp).clip(CircleShape).background(Color(t.accent)))
                            Spacer(Modifier.width(3.dp)); Box(Modifier.size(16.dp).clip(CircleShape).background(Color(t.accent2))) }
                    }
                    Text(t.name, style = T.label.copy(color = if (on) N.text else N.textMuted), maxLines = 2, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Text("Style · ${Looks.styles.first { it.code == style }.hint}", style = T.label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Looks.styles.forEach { s -> KChip(s.label, s.code == style) { style = s.code } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            KField("", free, { free = it }, Modifier.weight(1f), hint = "Or describe it (\"pink and playful\")")
            Spacer(Modifier.width(8.dp))
            FilledIconBtn(Icons.Rounded.ArrowUpward, "Send description", enabled = free.isNotBlank()) { onAnswer(free.trim()) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KButton("Kiln default") { onAnswer(Looks.DEFAULT) }
            Spacer(Modifier.weight(1f))
            KButton("Use this", Tone.Accent) { onAnswer(Looks.answer(theme, style)) }
        }
    }
}
