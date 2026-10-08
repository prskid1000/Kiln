package app.kiln.kit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

// ---------------------------------------------------------------- containers

/**
 * Card with every knob: `KCardBox(variant = KVariant.Tonal, tone = KTone.Ok, width = 160.dp, height = 120.dp) { … }`.
 * ([KCard] stays the simple outlined one.)
 */
@Composable
fun KCardBox(
    modifier: Modifier = Modifier,
    variant: KVariant = KVariant.Outline,
    tone: KTone = KTone.Neutral,
    width: Dp? = null,
    height: Dp? = null,
    padding: Dp = 14.dp,
    corner: Dp = kr(16),
    spacing: Dp = 8.dp,
    colors: KColors? = null,
    border: Dp = 1.dp,
    elevation: Dp = 0.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    val bg = colors?.container ?: when (variant) {
        KVariant.Filled -> if (tone == KTone.Neutral) Nocturne.surfaceHi else tone.color().copy(alpha = 0.30f)
        KVariant.Tonal -> if (tone == KTone.Neutral) Nocturne.surface else tone.color().copy(alpha = 0.14f)
        KVariant.Outline -> Nocturne.surface
        KVariant.Ghost -> Color.Transparent
    }
    val line = colors?.border ?: if (variant == KVariant.Outline) (if (tone == KTone.Neutral) Nocturne.neutral700 else tone.color().copy(alpha = 0.6f)) else null
    val body = @Composable {
        Column(
            modifier.then(if (width == null) Modifier.fillMaxWidth() else Modifier.width(width)).kSize(height = height)
                .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape) else Modifier)
                .kSurface(shape, bg, if (border > 0.dp) line else null, neutral = colors?.container == null && (tone == KTone.Neutral || variant == KVariant.Outline), borderWidth = border)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(spacing), content = content,
        )
    }
    // Cards always set their text colour, so they read right on any backdrop (glass, gradients, images).
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides (colors?.content ?: Nocturne.text), content = body)
}

/** Divider, optionally with a centred label ("or"). */
@Composable
fun KDivider(modifier: Modifier = Modifier, label: String? = null, thickness: Dp = 1.dp, vertical: Dp = 8.dp) {
    if (label == null) HorizontalDivider(modifier.padding(vertical = vertical), thickness = thickness, color = Nocturne.divider)
    else Row(modifier.fillMaxWidth().padding(vertical = vertical), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), thickness = thickness, color = Nocturne.divider)
        Text(label, color = Nocturne.textMuted, fontSize = kt(12), modifier = Modifier.padding(horizontal = 10.dp))
        HorizontalDivider(Modifier.weight(1f), thickness = thickness, color = Nocturne.divider)
    }
}

/** Expandable section (FAQ, details). Also "collapsible". */
@Composable
fun KAccordion(title: String, modifier: Modifier = Modifier, subtitle: String? = null, initiallyOpen: Boolean = false,
               icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    Column(modifier.fillMaxWidth().kSurface(RoundedCornerShape(kr(14)), Nocturne.surface, Nocturne.divider)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, tint = Nocturne.accent2, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Nocturne.textMuted)
            }
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, if (open) "Collapse" else "Expand", tint = Nocturne.textMuted)
        }
        AnimatedVisibility(open) { Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
    }
}

// ---------------------------------------------------------------- identity

/** Avatar: an image URL, else initials from [name], else [icon]; optional online dot. */
@Composable
fun KAvatar(name: String = "", modifier: Modifier = Modifier, imageUrl: String? = null, icon: ImageVector? = null,
            size: Dp = ks(40), tone: KTone = KTone.Accent, online: Boolean? = null) {
    Box(modifier.size(size)) {
        Box(Modifier.size(size).clip(CircleShape).background(tone.color().copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
            when {
                imageUrl != null -> AsyncImage(imageUrl, name, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
                name.isNotBlank() -> Text(name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() },
                    color = tone.color(), fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.Medium)
                icon != null -> Icon(icon, name, tint = tone.color(), modifier = Modifier.size(size * 0.55f))
            }
        }
        if (online != null) Box(Modifier.size(size * 0.28f).align(Alignment.BottomEnd).clip(CircleShape).background(Nocturne.bg).padding(2.dp)
            .clip(CircleShape).background(if (online) Nocturne.ok else Nocturne.neutral600))
    }
}

/** Overlapping avatars with "+N". */
@Composable
fun KAvatarGroup(names: List<String>, modifier: Modifier = Modifier, max: Int = 4, size: Dp = 32.dp) {
    Row(modifier) {
        names.take(max).forEachIndexed { i, n -> KAvatar(n, Modifier.offset(x = -(size * 0.3f) * i), size = size) }
        if (names.size > max) Box(Modifier.offset(x = -(size * 0.3f) * max).size(size).clip(CircleShape).background(Nocturne.surfaceHi),
            contentAlignment = Alignment.Center) { Text("+${names.size - max}", fontSize = (size.value * 0.34f).sp, color = Nocturne.text) }
    }
}

/** Count or dot badge on top of [content] (an icon, an avatar). count = 0 hides it; null shows a dot. */
@Composable
fun KBadge(count: Int?, modifier: Modifier = Modifier, tone: KTone = KTone.Danger, color: Color? = null, content: @Composable BoxScope.() -> Unit) {
    val badge = color ?: tone.color()
    Box(modifier) {
        content()
        if (count == null) Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(10.dp).clip(CircleShape).background(badge))
        else if (count > 0) Text(if (count > 99) "99+" else "$count", color = Nocturne.bg, fontSize = kt(10), fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-8).dp).clip(RoundedCornerShape(50)).background(badge)
                .padding(horizontal = 5.dp, vertical = 1.dp))
    }
}

/** Chip: label with optional icon; [onClose] adds an ✕ (input chip / tag you can remove). */
@Composable
fun KChip(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, tone: KTone = KTone.Neutral, selected: Boolean = false,
          onClick: (() -> Unit)? = null, onClose: (() -> Unit)? = null, colors: KColors? = null) {
    val (bg, fg, _) = resolveColors(if (selected) KTone.Accent else tone, KVariant.Tonal, colors)
    Row(modifier.height(ks(32)).kSurface(RoundedCornerShape(50), bg, fg.copy(alpha = 0.3f), neutral = false)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = fg, fontSize = kt(13), fontWeight = FontWeight.Medium)
        if (onClose != null) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.Close, "Remove $text", tint = fg, modifier = Modifier.size(16.dp).clickable(onClick = onClose)) }
    }
}

/** Keyboard key / shortcut cap ("Ctrl", "K"). */
@Composable
fun KKbd(key: String, modifier: Modifier = Modifier) =
    Text(key, color = Nocturne.text, fontSize = kt(12), fontFamily = Nocturne.mono,
        modifier = modifier.kSurface(RoundedCornerShape(kr(6)), Nocturne.surfaceHi, Nocturne.neutral700)
            .padding(horizontal = 6.dp, vertical = 2.dp))

/** Image from a URL with a rounded frame, aspect ratio and placeholder colour. */
@Composable
fun KImage(url: String, description: String, modifier: Modifier = Modifier, width: Dp? = null, height: Dp? = null,
           aspectRatio: Float? = 16f / 9f, corner: Dp = kr(14), crop: Boolean = true) =
    AsyncImage(url, description, contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
        modifier = modifier.then(if (width == null) Modifier.fillMaxWidth() else Modifier.width(width)).kSize(height = height)
            .then(if (aspectRatio != null && height == null) Modifier.aspectRatio(aspectRatio) else Modifier)
            .clip(RoundedCornerShape(corner)).background(Nocturne.surface))

// ---------------------------------------------------------------- feedback

/** Banner message: info / success / warning / error, optional title, action and dismiss. Also the "notice bar". */
@Composable
fun KAlert(message: String, modifier: Modifier = Modifier, tone: KTone = KTone.Accent, title: String? = null,
           icon: ImageVector? = null, actionLabel: String? = null, onAction: (() -> Unit)? = null, onDismiss: (() -> Unit)? = null,
           color: Color? = null) {
    val c = color ?: tone.color()
    val glyph = icon ?: when (tone) { KTone.Ok -> Icons.Filled.CheckCircle; KTone.Warn -> Icons.Filled.Warning
        KTone.Danger -> Icons.Filled.Error; else -> Icons.Filled.Info }
    Row(modifier.fillMaxWidth().kSurface(RoundedCornerShape(kr(12)), c.copy(alpha = if (Nocturne.isDark) 0.10f else 0.12f), c.copy(alpha = 0.22f), neutral = false)
        .padding(12.dp), verticalAlignment = Alignment.Top) {
        Icon(glyph, null, tint = c, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (title != null) Text(title, color = Nocturne.ink(c), style = MaterialTheme.typography.titleSmall)
            Text(message, color = Nocturne.text, style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) Text(actionLabel, color = Nocturne.ink(c), fontWeight = FontWeight.Medium, fontSize = kt(14),
                modifier = Modifier.padding(top = 4.dp).clickable(onClick = onAction))
        }
        if (onDismiss != null) Icon(Icons.Filled.Close, "Dismiss", tint = Nocturne.textMuted, modifier = Modifier.size(20.dp).clickable(onClick = onDismiss))
    }
}

/** Progress bar (0–1); [label] and the percentage above it; null [progress] = indeterminate. */
@Composable
fun KProgressBar(progress: Float?, modifier: Modifier = Modifier, label: String? = null, showPercent: Boolean = true,
                 tone: KTone = KTone.Accent, height: Dp = 8.dp, width: Dp? = null, color: Color? = null) {
    val bar = color ?: tone.color()
    Column(modifier.then(if (width == null) Modifier.fillMaxWidth() else Modifier.width(width)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null || (showPercent && progress != null)) Row(Modifier.fillMaxWidth()) {
            Text(label.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Nocturne.textLabel, modifier = Modifier.weight(1f))
            if (showPercent && progress != null) Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = bar)
        }
        val mod = Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50))
        if (progress == null) LinearProgressIndicator(mod, color = bar, trackColor = Nocturne.surfaceHi)
        else LinearProgressIndicator({ progress.coerceIn(0f, 1f) }, mod, color = bar, trackColor = Nocturne.surfaceHi,
            strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
    }
}

/** Circular progress (0–1) with text in the middle (goal rings, timers). */
@Composable
fun KProgressRing(progress: Float, modifier: Modifier = Modifier, size: Dp = ks(120), stroke: Dp = 10.dp, tone: KTone = KTone.Accent, color: Color? = null,
                  center: (@Composable () -> Unit)? = { Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.titleLarge) }) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        val c = color ?: tone.color()
        Canvas(Modifier.size(size)) {
            val s = stroke.toPx(); val d = this.size.minDimension - s
            drawArc(Nocturne.surfaceHi, 0f, 360f, false, Offset(s / 2, s / 2), Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
            drawArc(c, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(s / 2, s / 2), Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
        }
        center?.invoke()
    }
}

/** Spinner in any size (inline loading; [KLoading] fills the screen). */
@Composable
fun KSpinner(modifier: Modifier = Modifier, size: Dp = 24.dp, tone: KTone = KTone.Accent, stroke: Dp = 2.5.dp) =
    androidx.compose.material3.CircularProgressIndicator(modifier.size(size), color = tone.color(), strokeWidth = stroke)

/** Shimmering placeholder while content loads; [circle] for avatars. */
@Composable
fun KSkeleton(modifier: Modifier = Modifier, width: Dp? = null, height: Dp = 16.dp, corner: Dp = kr(8), circle: Boolean = false) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(0.35f, 0.75f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    Box(modifier.then(if (width == null && !circle) Modifier.fillMaxWidth() else Modifier.width(width ?: height)).height(height)
        .clip(if (circle) CircleShape else RoundedCornerShape(corner)).background(Nocturne.surfaceHi.copy(alpha = a)))
}

// ---------------------------------------------------------------- data

/** Label on the left, value on the right (details, receipts, settings summaries). */
@Composable
fun KKeyValue(key: String, value: String, modifier: Modifier = Modifier, valueTone: KTone? = null, bold: Boolean = false) =
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, color = Nocturne.textLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, color = valueTone?.color() ?: Nocturne.text, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    }

/** Simple table: header + rows of text; columns share width by [weights]. */
@Composable
fun KTable(headers: List<String>, rows: List<List<String>>, modifier: Modifier = Modifier, weights: List<Float> = headers.map { 1f },
           zebra: Boolean = true) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(kr(12))).border(1.dp, Nocturne.divider, RoundedCornerShape(kr(12)))) {
        Row(Modifier.fillMaxWidth().background(Nocturne.surfaceHi).padding(horizontal = 12.dp, vertical = 10.dp)) {
            headers.forEachIndexed { i, h -> Text(h, Modifier.weight(weights.getOrElse(i) { 1f }), color = Nocturne.textLabel, fontSize = kt(12), fontWeight = FontWeight.Medium) }
        }
        rows.forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth().background(if (zebra && r % 2 == 1) Nocturne.surface else Color.Transparent).padding(horizontal = 12.dp, vertical = 10.dp)) {
                row.forEachIndexed { i, c -> Text(c, Modifier.weight(weights.getOrElse(i) { 1f }), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** One event in a [KTimeline]. */
data class KTimelineItem(val title: String, val subtitle: String? = null, val time: String? = null, val tone: KTone = KTone.Accent, val done: Boolean = true)

/** Vertical timeline (order tracking, history). */
@Composable
fun KTimeline(items: List<KTimelineItem>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        items.forEachIndexed { i, it ->
            Row(Modifier.fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(24.dp)) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(if (it.done) it.tone.color() else Nocturne.neutral700))
                    if (i < items.lastIndex) Box(Modifier.width(2.dp).height(ks(44)).background(Nocturne.divider))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).padding(bottom = 12.dp)) {
                    Text(it.title, style = MaterialTheme.typography.titleSmall, color = if (it.done) Nocturne.text else Nocturne.textMuted)
                    if (it.subtitle != null) Text(it.subtitle, style = MaterialTheme.typography.bodySmall, color = Nocturne.textMuted)
                }
                if (it.time != null) Text(it.time, style = MaterialTheme.typography.labelSmall, color = Nocturne.textMuted)
            }
        }
    }
}

/** Step indicator for multi-step flows (1 — 2 — 3); [current] is 0-based. */
@Composable
fun KSteps(steps: List<String>, current: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        steps.forEachIndexed { i, s ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(2.dp).background(if (i == 0) Color.Transparent else if (i <= current) Nocturne.accent else Nocturne.divider))
                    Box(Modifier.size(28.dp).clip(CircleShape).background(if (i <= current) Nocturne.accent else Nocturne.surfaceHi), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = if (i <= current) Nocturne.bg else Nocturne.textLabel, fontSize = kt(13), fontWeight = FontWeight.Medium)
                    }
                    Box(Modifier.weight(1f).height(2.dp).background(if (i == steps.lastIndex) Color.Transparent else if (i < current) Nocturne.accent else Nocturne.divider))
                }
                Text(s, style = MaterialTheme.typography.labelSmall, color = if (i == current) Nocturne.text else Nocturne.textMuted,
                    modifier = Modifier.padding(top = 6.dp), maxLines = 1)
            }
        }
    }
}

/** Swipeable pages with dots (onboarding, photo galleries, featured cards). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KCarousel(count: Int, modifier: Modifier = Modifier, height: Dp = 200.dp, showDots: Boolean = true, peek: Dp = 0.dp,
              page: @Composable (Int) -> Unit) {
    val state = rememberPagerState { count }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(state, Modifier.fillMaxWidth().height(height), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = peek),
            pageSpacing = 12.dp) { page(it) }
        if (showDots && count > 1) KPageDots(count, state.currentPage, Modifier.padding(top = 10.dp))
    }
}

/** Page indicator dots. */
@Composable
fun KPageDots(count: Int, current: Int, modifier: Modifier = Modifier) =
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i -> Box(Modifier.height(6.dp).width(if (i == current) 18.dp else 6.dp).clip(RoundedCornerShape(50))
            .background(if (i == current) Nocturne.accent else Nocturne.neutral700)) }
    }

/** Page numbers with previous / next. */
@Composable
fun KPagination(page: Int, pages: Int, onPage: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        KButton("Prev", variant = KVariant.Ghost, tone = KTone.Neutral, size = KSize.Small, enabled = page > 1) { onPage(page - 1) }
        val shown = (maxOf(1, page - 2)..minOf(pages, page + 2)).toList()
        shown.forEach { p -> KButton("$p", variant = if (p == page) KVariant.Filled else KVariant.Ghost, tone = if (p == page) KTone.Accent else KTone.Neutral,
            size = KSize.Small, width = 40.dp) { onPage(p) } }
        KButton("Next", variant = KVariant.Ghost, tone = KTone.Neutral, size = KSize.Small, enabled = page < pages) { onPage(page + 1) }
    }
}
