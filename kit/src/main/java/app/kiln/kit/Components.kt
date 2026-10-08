package app.kiln.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Small uppercase accent heading above a group ("Nocturne kicker"). */
@Composable
fun KSection(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), color = Nocturne.ink(Nocturne.accent), fontSize = kt(11), fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** One list row: optional leading icon, title, subtitle, trailing slot. */
@Composable
fun KListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(kr(8)))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Nocturne.accent2, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Nocturne.textMuted,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

/** A setting with a switch. */
@Composable
fun KSwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) =
    KListRow(title = title, subtitle = subtitle, onClick = { onChange(!checked) }, trailing = {
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(
            checkedTrackColor = Nocturne.accent, checkedThumbColor = Nocturne.bg,
            uncheckedTrackColor = Nocturne.surfaceHi, uncheckedBorderColor = Nocturne.divider))
    })

/** Centered empty state: icon, title, body, optional action. */
@Composable
fun KEmptyState(title: String, body: String, icon: ImageVector? = null,
                actionLabel: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Nocturne.accent, modifier = Modifier.size(48.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Nocturne.textMuted, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) OutlinedButton(onClick = onAction) { Text(actionLabel) }
    }
}

/** Colour roles: the theme's two accents, a neutral, and the status colours. */
enum class KTone { Accent, Accent2, Neutral, Ok, Warn, Danger }

/** Small status pill. */
@Composable
fun KTag(text: String, tone: KTone = KTone.Neutral, modifier: Modifier = Modifier) {
    val (ground, ink) = when (tone) {
        KTone.Accent -> Nocturne.accent800 to Nocturne.accent100
        KTone.Accent2 -> Nocturne.accent2.copy(alpha = 0.16f) to Nocturne.ink(Nocturne.accent2)
        KTone.Neutral -> Nocturne.neutral800 to Nocturne.neutral100
        KTone.Ok -> Nocturne.ok.copy(alpha = 0.16f) to Nocturne.ok
        KTone.Warn -> Nocturne.warn.copy(alpha = 0.16f) to Nocturne.warn
        KTone.Danger -> Nocturne.danger.copy(alpha = 0.16f) to Nocturne.danger
    }
    // On glass a faint tint vanishes into the pane: give the pill more body and lift the text.
    val glass = Nocturne.surfaceStyle == KStyle.Glass
    val g = if (glass) tone.color().copy(alpha = 0.30f) else ground
    val i = if (glass) androidx.compose.ui.graphics.lerp(tone.color(), if (Nocturne.isDark) Color.White else Color.Black, 0.5f) else ink
    Text(text, color = i, fontSize = kt(11), fontWeight = FontWeight.Medium,
        modifier = modifier.clip(RoundedCornerShape(kr(6))).background(g).padding(horizontal = 10.dp, vertical = 3.dp))
}

/** Metric tile: label over a big value, optional caption. */
@Composable
fun KStat(label: String, value: String, modifier: Modifier = Modifier, caption: String? = null) =
    KCardBox(modifier) {
        Text(label.uppercase(), color = Nocturne.textMuted, fontSize = kt(11), letterSpacing = 1.sp)
        Text(value, style = MaterialTheme.typography.headlineMedium)
        if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall, color = Nocturne.textMuted)
    }

/** Full-area loading spinner. */
@Composable
fun KLoading(modifier: Modifier = Modifier) =
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Nocturne.accent) }

/** Inline error with an optional retry. */
@Composable
fun KError(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) =
    KCardBox(modifier) {
        Text(message, color = Nocturne.ink(Nocturne.danger), style = MaterialTheme.typography.bodyMedium)
        if (onRetry != null) TextButton(onClick = onRetry) { Text("Retry") }
    }

/** Yes/no confirmation dialog. Show it while [open] is true. */
@Composable
fun KConfirm(open: Boolean, title: String, body: String, confirmLabel: String = "OK",
             destructive: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    if (!open) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) }, text = { Text(body) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) {
            Text(confirmLabel, color = if (destructive) Nocturne.danger else Nocturne.accent)
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Nocturne.surface,
    )
}

/** Vertical gap. */
@Composable fun VGap(dp: Int = 8) = Spacer(Modifier.height(dp.dp))
/** Horizontal gap. */
@Composable fun HGap(dp: Int = 8) = Spacer(Modifier.width(dp.dp))

/** Colour for a tone, for custom drawing. */
fun KTone.color(): Color = when (this) {
    KTone.Accent -> Nocturne.accent; KTone.Accent2 -> Nocturne.accent2; KTone.Neutral -> Nocturne.neutral300
    KTone.Ok -> Nocturne.ok; KTone.Warn -> Nocturne.warn; KTone.Danger -> Nocturne.danger
}
