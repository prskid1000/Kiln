package app.kiln.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kiln.ui.theme.N
import app.kiln.ui.theme.T
import app.kiln.ui.theme.vInset

enum class Tone { Accent, Neutral, Ok, Danger, Warn, Outline }

@Composable
fun KTag(text: String, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier) {
    val (ground, ink) = when (tone) {
        Tone.Accent -> N.accent800 to N.accent100
        Tone.Ok -> N.ok.copy(alpha = N.statusGround) to N.ok
        Tone.Danger -> N.danger.copy(alpha = N.statusGround) to N.danger
        Tone.Warn -> N.warn.copy(alpha = N.statusGround) to N.warn
        Tone.Outline -> Color.Transparent to N.accent
        Tone.Neutral -> N.neutral800 to N.neutral100
    }
    Text(text, style = T.label.copy(color = ink, fontSize = 11.sp),
        modifier = modifier.clip(N.shapeTag)
            .then(if (tone == Tone.Outline) Modifier.border(1.dp, N.accent, N.shapeTag) else Modifier.background(ground))
            .padding(horizontal = 10.dp, vertical = 3.dp))
}

@Composable
fun KButton(label: String, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val (ink, ring, bg) = when (tone) {
        Tone.Accent -> Triple(N.accent, N.accent, N.accent.copy(alpha = 0.10f))
        Tone.Danger -> Triple(N.danger, N.danger.copy(alpha = 0.6f), N.danger.copy(alpha = 0.08f))
        else -> Triple(N.textLabel, N.divider, Color.Transparent)
    }
    Text(label, style = T.control.copy(color = if (enabled) ink else N.textMuted),
        modifier = modifier.clip(N.shapeMd).background(bg).border(1.dp, if (enabled) ring else N.divider, N.shapeMd)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = N.s4, vertical = N.s3))
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) = Box(modifier.size(8.dp).clip(CircleShape).background(color))

/** Labeled single-line field in a Nocturne inset well. */
@Composable
fun KField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
           mono: Boolean = false, hint: String = "", singleLine: Boolean = true) {
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) { Text(label, style = T.label); Spacer(Modifier.height(4.dp)) }
        Box(Modifier.fillMaxWidth().vInset().padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (value.isEmpty() && hint.isNotEmpty()) Text(hint, style = (if (mono) T.mono else T.body).copy(color = N.textMuted))
            BasicTextField(value, onChange, singleLine = singleLine, cursorBrush = SolidColor(N.accent),
                textStyle = if (mono) T.mono.copy(color = N.text) else T.body, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Segmented choice (Warden's in-page tabs). */
@Composable
fun SegTabs(options: List<Pair<String, String>>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(modifier.fillMaxWidth().clip(N.shapeMd).border(1.dp, N.divider, N.shapeMd)) {
        options.forEachIndexed { i, (label, count) ->
            if (i > 0) Box(Modifier.width(1.dp).height(44.dp).background(N.divider))
            val on = i == selected
            Row(Modifier.weight(1f).clickable { onSelect(i) }
                .then(if (on) Modifier.background(N.accent.copy(alpha = 0.10f)) else Modifier)
                .padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = T.control.copy(color = if (on) N.accent else N.textLabel))
                if (count.isNotEmpty()) { Spacer(Modifier.width(6.dp)); Text(count, style = T.label.copy(color = if (on) N.accent else N.textMuted)) }
            }
        }
    }
}

val MonoBody = TextStyle(fontSize = 12.sp)
