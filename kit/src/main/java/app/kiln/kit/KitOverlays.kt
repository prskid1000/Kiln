@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.kiln.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- overlays

/**
 * Dialog with your own content (forms, pickers). Shown while [open]; [onConfirm] then closes it.
 * Leave [confirmLabel] null for an info dialog with just "Close".
 */
@Composable
fun KDialog(open: Boolean, title: String, onDismiss: () -> Unit, confirmLabel: String? = null, onConfirm: () -> Unit = {},
            destructive: Boolean = false, dismissLabel: String = if (confirmLabel == null) "Close" else "Cancel",
            content: @Composable ColumnScope.() -> Unit) {
    if (!open) return
    AlertDialog(onDismissRequest = onDismiss, containerColor = Nocturne.surface,
        title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content) },
        confirmButton = { if (confirmLabel != null) TextButton({ onConfirm(); onDismiss() }) {
            Text(confirmLabel, color = if (destructive) Nocturne.danger else Nocturne.accent) } },
        dismissButton = { TextButton(onDismiss) { Text(dismissLabel, color = Nocturne.textLabel) } })
}

/** Bottom sheet (actions, filters, details). Shown while [open]. Also the "drawer" / "sheet". */
@Composable
fun KBottomSheet(open: Boolean, onDismiss: () -> Unit, title: String? = null, fullHeight: Boolean = false,
                 content: @Composable ColumnScope.() -> Unit) {
    if (!open) return
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = fullHeight), containerColor = Nocturne.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** One entry in a [KMenu]. */
data class KMenuItem(val label: String, val icon: ImageVector? = null, val destructive: Boolean = false, val enabled: Boolean = true,
                     val onClick: () -> Unit)

/** Dropdown / context menu anchored to [anchor] (usually a KIconButton with ⋮). Also "popover". */
@Composable
fun KMenu(items: List<KMenuItem>, modifier: Modifier = Modifier, anchor: @Composable (open: () -> Unit) -> Unit) {
    val state = remember { androidx.compose.runtime.mutableStateOf(false) }
    Box(modifier) {
        anchor { state.value = true }
        DropdownMenu(state.value, { state.value = false }, containerColor = Nocturne.surfaceHi) {
            items.forEach { it ->
                DropdownMenuItem(text = { Text(it.label, color = if (it.destructive) Nocturne.danger else Nocturne.text) },
                    leadingIcon = it.icon?.let { ic -> { Icon(ic, null, tint = if (it.destructive) Nocturne.danger else Nocturne.textLabel) } },
                    enabled = it.enabled, onClick = { state.value = false; it.onClick() })
            }
        }
    }
}

/** Long-press tooltip on [content] (explain an icon). */
@Composable
fun KTooltip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    TooltipBox(TooltipDefaults.rememberTooltipPositionProvider(), { PlainTooltip { Text(text) } }, rememberTooltipState(), modifier) { content() }

/** Toasts: `val toast = rememberKToast()`, put `KToastHost(toast)` in your screen, then `toast.show("Saved")`. */
class KToast internal constructor(internal val host: SnackbarHostState, private val scope: kotlinx.coroutines.CoroutineScope) {
    internal var tone: KTone = KTone.Neutral
    /** Show [message]; with [action], [onAction] runs if it's tapped (e.g. "Undo"). */
    fun show(message: String, tone: KTone = KTone.Neutral, action: String? = null, long: Boolean = false, onAction: () -> Unit = {}) {
        this.tone = tone
        scope.launch {
            val r = host.showSnackbar(message, action, withDismissAction = action == null,
                duration = if (long || action != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) onAction()
        }
    }
}

@Composable
fun rememberKToast(): KToast {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return remember { KToast(SnackbarHostState(), scope) }
}

/** Where toasts appear; place it last in a Box so it floats at the bottom. */
@Composable
fun BoxScope.KToastHost(toast: KToast, modifier: Modifier = Modifier) =
    SnackbarHost(toast.host, modifier.align(Alignment.BottomCenter).padding(16.dp)) { data ->
        Snackbar(data, containerColor = Nocturne.surfaceHi, contentColor = if (toast.tone == KTone.Neutral) Nocturne.text else toast.tone.color(),
            actionColor = Nocturne.accent, shape = RoundedCornerShape(kr(12)))
    }

// ---------------------------------------------------------------- gestures

/**
 * A row you swipe to delete (right to left) or archive (left to right). The callback decides:
 * return true to let the row go (remove it from your list), false to snap it back.
 */
@Composable
fun KSwipeRow(modifier: Modifier = Modifier, onDelete: (() -> Boolean)? = null, onArchive: (() -> Boolean)? = null,
              content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) { SwipeToDismissBoxValue.EndToStart -> onDelete?.invoke() ?: false
            SwipeToDismissBoxValue.StartToEnd -> onArchive?.invoke() ?: false; else -> false }
    })
    SwipeToDismissBox(state, modifier = modifier, enableDismissFromStartToEnd = onArchive != null, enableDismissFromEndToStart = onDelete != null,
        backgroundContent = {
            val toDelete = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(kr(12))).background((if (toDelete) Nocturne.danger else Nocturne.ok).copy(alpha = 0.25f))
                .padding(horizontal = 20.dp), contentAlignment = if (toDelete) Alignment.CenterEnd else Alignment.CenterStart) {
                Icon(if (toDelete) Icons.Filled.Delete else Icons.Filled.Archive, if (toDelete) "Delete" else "Archive",
                    tint = if (toDelete) Nocturne.danger else Nocturne.ok)
            }
        }) { Box(Modifier.background(Nocturne.bg)) { content() } }
}

/** Pull down to refresh; set [refreshing] while your load runs. */
@Composable
fun KPullRefresh(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) =
    PullToRefreshBox(refreshing, onRefresh, modifier, content = content)

// ---------------------------------------------------------------- navigation & layout

/** One destination in a [KBottomBar]. */
data class KNavItem(val label: String, val icon: ImageVector, val badge: Int? = null)

/**
 * Bottom navigation bar. It draws where you put it, so put it in a screen's bottom slot:
 * `KilnScreen("Home", bottomBar = { KBottomBar(items, tab, { tab = it }) }) { pad -> … }`.
 * For an app whose top level is tabs, [KilnTabs] does this (and the rail on tablets) for you.
 */
@Composable
fun KBottomBar(items: List<KNavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) =
    NavigationBar(modifier, containerColor = Nocturne.surface) {
        items.forEachIndexed { i, it ->
            NavigationBarItem(i == selected, { onSelect(i) }, icon = {
                if (it.badge != null) KBadge(it.badge) { Icon(it.icon, it.label) } else Icon(it.icon, it.label)
            }, label = { Text(it.label) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Nocturne.accent100,
                indicatorColor = Nocturne.accent800, selectedTextColor = Nocturne.accent100, unselectedIconColor = Nocturne.textLabel,
                unselectedTextColor = Nocturne.textLabel))
        }
    }

/** Grid of [count] items with [columns] fixed columns, or adaptive to [minCellWidth]. */
@Composable
fun KGrid(modifier: Modifier = Modifier, columns: Int? = 2, minCellWidth: Dp = 160.dp, spacing: Dp = 12.dp,
          contentPadding: PaddingValues = PaddingValues(0.dp), content: LazyGridScope.() -> Unit) =
    LazyVerticalGrid(if (columns != null) GridCells.Fixed(columns) else GridCells.Adaptive(minCellWidth), modifier,
        contentPadding = contentPadding, horizontalArrangement = Arrangement.spacedBy(spacing), verticalArrangement = Arrangement.spacedBy(spacing),
        content = content)

// ---------------------------------------------------------------- charts

/** Bar chart: one bar per value, labels under them. */
@Composable
fun KBarChart(values: List<Float>, labels: List<String> = emptyList(), modifier: Modifier = Modifier, height: Dp = ks(160),
              tone: KTone = KTone.Accent, highlight: Int? = null, valueFormat: ((Float) -> String)? = null, color: Color? = null) {
    val bar = color ?: tone.color()
    val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1e-6f)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            values.forEachIndexed { i, v ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    if (valueFormat != null) Text(valueFormat(v), fontSize = kt(10), color = Nocturne.textMuted)
                    Box(Modifier.fillMaxWidth().height(height * 0.85f * (v / max)).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .background(if (highlight == null || highlight == i) bar else bar.copy(alpha = 0.35f)))
                }
            }
        }
        if (labels.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEach { Text(it, Modifier.weight(1f), fontSize = kt(11), color = Nocturne.textMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1) }
        }
    }
}

/** Line chart / sparkline; [fill] shades the area under the line. */
@Composable
fun KLineChart(values: List<Float>, modifier: Modifier = Modifier, height: Dp = ks(140), tone: KTone = KTone.Accent, fill: Boolean = true,
               stroke: Dp = 2.5.dp, showDots: Boolean = false, color: Color? = null) {
    val c = color ?: tone.color()
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (values.size < 2) return@Canvas
        val min = values.min(); val max = values.max(); val span = (max - min).takeIf { it > 0f } ?: 1f
        val pts = values.mapIndexed { i, v -> Offset(i * size.width / (values.size - 1), size.height - (v - min) / span * size.height * 0.9f - size.height * 0.05f) }
        val line = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
        if (fill) drawPath(Path().apply { addPath(line); lineTo(pts.last().x, size.height); lineTo(0f, size.height); close() }, c.copy(alpha = 0.15f))
        drawPath(line, c, style = Stroke(stroke.toPx(), cap = StrokeCap.Round))
        if (showDots) pts.forEach { drawCircle(c, stroke.toPx() * 1.6f, it) }
    }
}

/** One slice of a [KDonutChart]. */
data class KSlice(val label: String, val value: Float, val tone: KTone)

/** Donut / pie chart with a legend; [center] text in the hole. */
@Composable
fun KDonutChart(slices: List<KSlice>, modifier: Modifier = Modifier, size: Dp = ks(140), stroke: Dp = 22.dp, center: String? = null,
                legend: Boolean = true) {
    val total = slices.sumOf { it.value.toDouble() }.toFloat().coerceAtLeast(1e-6f)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size)) {
                val s = stroke.toPx(); val d = this.size.minDimension - s
                var start = -90f
                slices.forEach { sl -> val sweep = 360f * sl.value / total
                    drawArc(sl.tone.color(), start, sweep - 1.5f, false, Offset(s / 2, s / 2), Size(d, d), style = Stroke(s)); start += sweep }
            }
            if (center != null) Text(center, style = MaterialTheme.typography.titleMedium)
        }
        if (legend) Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            slices.forEach { sl -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(sl.tone.color())); Spacer(Modifier.width(8.dp))
                Text("${sl.label} · ${(sl.value / total * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            } }
        }
    }
}


// ---------------------------------------------------------------- theming

/**
 * Lets the app's user choose a look: a swatch per theme. Keep the choice and feed it to KilnTheme:
 * ```
 * var themeName by rememberStored("theme", KThemes.Nocturne.name)
 * KilnTheme(theme = KThemes.named(themeName) ?: KThemes.Nocturne) { … KThemePicker(themeName, { themeName = it.name }) … }
 * ```
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun KThemePicker(selected: String, onSelect: (KTheme) -> Unit, modifier: Modifier = Modifier, themes: List<KTheme> = KThemes.all,
                 swatch: Dp = ks(56)) {
    androidx.compose.foundation.layout.FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        themes.forEach { t ->
            val on = t.name.equals(selected, ignoreCase = true)
            Column(Modifier.width(swatch + 16.dp).clip(RoundedCornerShape(kr(12))).clickable { onSelect(t) }.padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(swatch).clip(CircleShape).background(t.palette.bg)
                    .border(if (on) 3.dp else 1.dp, if (on) Nocturne.accent else Nocturne.divider, CircleShape), contentAlignment = Alignment.Center) {
                    Row { Box(Modifier.size(swatch * 0.28f).clip(CircleShape).background(t.palette.accent))
                        Spacer(Modifier.width(3.dp)); Box(Modifier.size(swatch * 0.28f).clip(CircleShape).background(t.palette.accent2)) }
                }
                Text(t.name, style = MaterialTheme.typography.labelSmall, color = if (on) Nocturne.text else Nocturne.textMuted,
                    maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
