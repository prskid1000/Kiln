package app.kiln.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
           mono: Boolean = false, hint: String = "", singleLine: Boolean = true, secret: Boolean = false) {
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) { Text(label, style = T.label); Spacer(Modifier.height(4.dp)) }
        Box(Modifier.fillMaxWidth().vInset().padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (value.isEmpty() && hint.isNotEmpty()) Text(hint, style = (if (mono) T.mono else T.body).copy(color = N.textMuted))
            BasicTextField(value, onChange, singleLine = singleLine, cursorBrush = SolidColor(N.accent),
                visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                textStyle = if (mono) T.mono.copy(color = N.text) else T.body, modifier = Modifier.fillMaxWidth().fieldLabel(label.ifEmpty { hint }))
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

/** Names a bare text field for TalkBack and UI automation (its placeholder is a separate Text). */
fun Modifier.fieldLabel(label: String): Modifier = this.semantics { contentDescription = label }

/** Top app bar: optional back arrow, title + subtitle, trailing actions. */
@Composable
fun KTopBar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, modifier: Modifier = Modifier,
            leading: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconBtn(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
        leading?.let { it(); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = if (subtitle == null && onBack == null) T.h3 else T.cardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = T.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

/** 48dp icon button. */
@Composable
fun IconBtn(icon: ImageVector, desc: String, modifier: Modifier = Modifier, tint: Color = N.textLabel,
            enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = if (enabled) tint else N.textMuted.copy(alpha = 0.35f), modifier = Modifier.size(22.dp))
    }
}

/** Filled circular accent button (send, run). */
@Composable
fun FilledIconBtn(icon: ImageVector, desc: String, modifier: Modifier = Modifier, container: Color = N.accent,
                  enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.size(44.dp).clip(CircleShape).background(if (enabled) container else N.neutral800)
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = if (enabled) N.bg else N.textMuted, modifier = Modifier.size(22.dp))
    }
}

private val tileHues = listOf(0xFF9184D9, 0xFF7FB69A, 0xFFD9A48A, 0xFF84AED9, 0xFFC98AD9, 0xFFD9C48A).map { Color(it) }

/** The app's tile: its initial on a hue picked from its name — stable per project. */
@Composable
fun AppTile(label: String, key: String, size: Dp = 44.dp) {
    val hue = tileHues[(key.hashCode() and 0x7fffffff) % tileHues.size]
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(hue.copy(alpha = 0.18f))
        .border(1.dp, hue.copy(alpha = 0.45f), RoundedCornerShape(size * 0.28f)), contentAlignment = Alignment.Center) {
        Text(label.trim().take(1).uppercase().ifBlank { "?" }, style = T.cardTitle.copy(color = hue, fontSize = (size.value * 0.42f).sp))
    }
}

/** Launcher icons read from built APKs, keyed by path + mtime so a rebuild shows the new icon. */
object IconCache {
    /** Bumped after anything that may have built an APK; icons re-check their file when it changes. */
    val version = kotlinx.coroutines.flow.MutableStateFlow(0)

    // ConcurrentHashMap can't hold null: an APK without a readable icon is remembered as NONE.
    private object NONE
    private val map = java.util.concurrent.ConcurrentHashMap<String, Any>()
    fun load(ctx: android.content.Context, apk: java.io.File, px: Int): androidx.compose.ui.graphics.ImageBitmap? {
        if (!apk.isFile) return null
        val v = map.getOrPut("${apk.path}@${apk.lastModified()}@$px") {
            runCatching {
                val pm = ctx.packageManager
                val info = pm.getPackageArchiveInfo(apk.path, 0)?.applicationInfo ?: return@runCatching null
                info.sourceDir = apk.path; info.publicSourceDir = apk.path
                info.loadIcon(pm).toBitmap(px, px).asImageBitmap()
            }.getOrNull() ?: NONE
        }
        return v as? androidx.compose.ui.graphics.ImageBitmap
    }
}

/** The app's own launcher icon (from its last build), or its letter tile before the first build. */
@Composable
fun ProjectIcon(project: app.kiln.build.Project, label: String, size: Dp = 44.dp) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pkg = remember(project) { runCatching { project.meta().`package` }.getOrDefault(app.kiln.build.Project.packageFor(project.name)) }
    val version by IconCache.version.collectAsState()
    val apk = java.io.File(project.buildDir, "$pkg.apk")
    val stamp = remember(version) { apk.lastModified() }
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val bmp by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, apk.path, stamp, px) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { IconCache.load(ctx, apk, px) }
    }
    val b = bmp
    if (b == null) AppTile(label, project.name, size)
    else androidx.compose.foundation.Image(b, null, Modifier.size(size).clip(RoundedCornerShape(size * 0.28f))
        .border(1.dp, N.cardRingSm, RoundedCornerShape(size * 0.28f)))
}

/** Pill tab strip with a sliding indicator. */
@Composable
fun PillTabs(options: List<Pair<ImageVector, String>>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(22.dp)).background(N.surface)
        .border(1.dp, N.cardRingSm, RoundedCornerShape(22.dp)).padding(4.dp)) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * selected, label = "tab")
        Box(Modifier.offset(x = x).width(w).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(N.accent800))
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, (icon, label) ->
                val on = i == selected
                Row(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp)).clickable { onSelect(i) },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = if (on) N.accent100 else N.textMuted, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = T.control.copy(fontSize = 13.sp, color = if (on) N.accent100 else N.textLabel))
                }
            }
        }
    }
}

/** Small filter chip. */
@Composable
fun KChip(text: String, on: Boolean, modifier: Modifier = Modifier, color: Color = N.accent, onClick: () -> Unit) {
    Text(text, style = T.label.copy(color = if (on) color else N.textMuted, fontSize = 12.sp),
        modifier = modifier.clip(RoundedCornerShape(14.dp)).background(if (on) color.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (on) color.copy(alpha = 0.5f) else N.divider, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp))
}

/** Centered empty state. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(N.accent800.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = N.accent2, modifier = Modifier.size(26.dp))
        }
        Text(title, style = T.cardTitle)
        Text(body, style = T.bodySmall.copy(color = N.textMuted, textAlign = TextAlign.Center))
        action?.invoke()
    }
}

fun relativeTime(ms: Long, now: Long = System.currentTimeMillis()): String {
    val s = (now - ms) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86_400 -> "${s / 3600}h ago"
        s < 7 * 86_400 -> "${s / 86_400}d ago"
        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(ms))
    }
}

fun humanBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
    else -> "%.1f MB".format(n / 1048576.0)
}
