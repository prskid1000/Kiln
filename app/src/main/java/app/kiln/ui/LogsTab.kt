package app.kiln.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kiln.Graph
import app.kiln.device.Warden
import app.kiln.ui.theme.N
import app.kiln.ui.theme.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class LogLine(val time: String, val level: Char, val tag: String, val msg: String)

private val THREADTIME = Regex("""^\d\d-\d\d (\d\d:\d\d:\d\d\.\d+)\s+\d+\s+\d+\s+([VDIWEF])\s+(.*?)\s*: (.*)$""")

private fun parseLog(raw: String): List<LogLine> = raw.lines().mapNotNull { l ->
    if (l.isBlank() || l.startsWith("---------")) null
    else THREADTIME.find(l)?.let { m -> LogLine(m.groupValues[1].take(12), m.groupValues[2][0], m.groupValues[3], m.groupValues[4]) }
        ?: LogLine("", ' ', "", l)
}

private fun levelColor(c: Char): Color = when (c) {
    'E', 'F' -> N.danger; 'W' -> N.warn; 'I' -> N.ok; 'D' -> N.accent2; else -> N.textMuted
}

private val LEVELS = listOf("V" to "All", "D" to "Debug", "I" to "Info", "W" to "Warn", "E" to "Error")

/** This app's logcat, live while the tab is open, plus its newest crash with a one-tap hand-off to the agent. */
@Composable
fun LogsTab(vm: KilnVM, ps: ProjectState, onAskFix: (String) -> Unit) {
    val warden by vm.warden.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    var level by rememberSaveable { mutableStateOf("V") }
    var query by rememberSaveable { mutableStateOf("") }
    var paused by remember { mutableStateOf(false) }
    var since by remember { mutableStateOf<String?>(null) }
    var lines by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    var crash by remember { mutableStateOf<String?>(null) }
    var dismissed by remember { mutableStateOf<String?>(null) }
    val ready = warden == Warden.Status.READY
    val isInstalled = ps.pkg in installed

    LaunchedEffect(ready, isInstalled, level, since, paused) {
        if (!ready || !isInstalled || paused) return@LaunchedEffect
        while (true) {
            val raw = withContext(Dispatchers.IO) { runCatching { Graph.device.logcat(ps.pkg, since, level, 800) }.getOrDefault("") }
            lines = parseLog(raw)
            crash = withContext(Dispatchers.IO) { runCatching { Graph.device.lastCrash(ps.pkg, since) }.getOrNull() }
            delay(1500)
        }
    }

    if (!ready) { EmptyState(Icons.Rounded.ReceiptLong, "Warden isn't connected", "Logs are read from the device through Warden."); return }
    if (!isInstalled) { EmptyState(Icons.Rounded.ReceiptLong, "Not installed yet", "Run the app to see its logs here."); return }

    Column(Modifier.fillMaxSize()) {
        // Search + actions
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(N.shapeLg).background(N.surface).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = N.textMuted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Filter", style = T.body.copy(color = N.textMuted))
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = T.body, cursorBrush = SolidColor(N.accent),
                        modifier = Modifier.fillMaxWidth().fieldLabel("Filter logs"))
                }
            }
            IconBtn(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (paused) "Resume" else "Pause") { paused = !paused }
            IconBtn(Icons.Rounded.ClearAll, "Clear") {
                // New marker: show only what happens from now on.
                since = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
                lines = emptyList(); crash = null
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LEVELS.forEach { (l, label) -> KChip(label, level == l, color = if (l == "V") N.accent else levelColor(l[0])) { level = l } }
        }
        crash?.takeIf { it != dismissed }?.let { c ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(N.shapeLg).background(N.danger.copy(alpha = 0.10f))
                .border(1.dp, N.danger.copy(alpha = 0.4f), N.shapeLg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.BugReport, null, tint = N.danger, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("The app crashed", style = T.subtitle.copy(color = N.danger), modifier = Modifier.weight(1f))
                }
                Text(c.trim(), style = T.monoSmall.copy(color = N.text), maxLines = 6, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KButton("Dismiss") { dismissed = c }
                    KButton("Ask Kiln to fix", Tone.Accent) { dismissed = c; onAskFix("The app crashed. Find the cause and fix it, then verify it runs:\n\n" +
                        // The top of the trace is enough to start; the agent fetches the rest with last_crash.
                        c.trim().lines().take(8).joinToString("\n")) }
                }
            }
        }
        val shown = remember(lines, query) {
            if (query.isBlank()) lines else lines.filter { query.lowercase() in (it.tag + " " + it.msg).lowercase() }
        }
        val list = rememberLazyListState()
        LaunchedEffect(shown.size) { if (shown.isNotEmpty() && !list.canScrollForward) list.scrollToItem(shown.size - 1) }
        if (shown.isEmpty()) EmptyState(Icons.Rounded.ReceiptLong, if (paused) "Paused" else "No log lines yet",
            if (since != null) "Cleared — new lines will appear as the app logs." else "Open the app and interact with it.")
        else Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            LazyColumn(state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                itemsIndexed(shown, key = { i, _ -> i }) { _, l ->
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(color = N.textMuted)) { append(l.time.padEnd(13)) }
                        withStyle(SpanStyle(color = levelColor(l.level))) { append("${l.level} ") }
                        if (l.tag.isNotEmpty()) withStyle(SpanStyle(color = N.accent2)) { append(l.tag); append(": ") }
                        withStyle(SpanStyle(color = if (l.level == 'E' || l.level == 'F') N.danger else N.text)) { append(l.msg) }
                    }, style = T.monoSmall, softWrap = false, modifier = Modifier.padding(vertical = 1.dp))
                }
            }
        }
    }
}
