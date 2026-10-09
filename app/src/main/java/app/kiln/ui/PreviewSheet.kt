package app.kiln.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.kiln.ui.theme.N
import app.kiln.ui.theme.T
import app.kiln.ui.theme.vCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app as the agent sees it, live, on the hidden test display. "Use" passes taps and swipes
 * through; "Select" picks an element to ask Kiln about, or to change its text right away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewSheet(vm: KilnVM, ps: ProjectState, onDismiss: () -> Unit, onAsk: (String) -> Unit) {
    val name = ps.project.name
    val scope = rememberCoroutineScope()
    // Launching for the preview hides the phone's crash dialogs: they come back when it closes, unless a run still needs them hidden.
    // Hidden again each time it opens (the app may already be showing, so no launch hides them).
    androidx.compose.runtime.LaunchedEffect(Unit) { KilnVM.runScope.launch { runCatching { app.kiln.Graph.testDevice.hideCrashDialogs() } } }
    androidx.compose.runtime.DisposableEffect(Unit) {
        // The sheet covers the chat's approval and question cards: their notifications must post meanwhile.
        if (app.kiln.agent.Attention.onScreen == name) app.kiln.agent.Attention.onScreen = null
        onDispose {
            if (app.kiln.agent.Attention.onScreen == null) app.kiln.agent.Attention.onScreen = name
            if (KilnVM.idle()) KilnVM.runScope.launch { runCatching { app.kiln.Graph.testDevice.restoreCrashDialogs() } }
        }
    }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var disp by remember { mutableStateOf(0 to 0) }
    var status by remember { mutableStateOf<String?>(null) }
    var select by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Picked?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        disp = vm.previewSize()
        if (!vm.previewIsShowing(name)) { status = "Opening the app…"; status = vm.previewLaunch(name) }
        while (true) { vm.previewFrame()?.let { frame = it.asImageBitmap() }; delay(350) }
    }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = N.surface) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Preview", style = T.cardTitle, modifier = Modifier.weight(1f))
                KChip("Use", !select) { select = false; picked = null }
                Spacer(Modifier.width(6.dp))
                KChip("Select", select) { select = true }
                IconBtn(Icons.AutoMirrored.Rounded.ArrowBack, "Back in the app") { scope.launch { vm.previewDevice.key("BACK") } }
                IconBtn(Icons.Rounded.Refresh, "Restart the app") { scope.launch { picked = null; status = vm.previewLaunch(name) } }
            }
            Text(if (select) "Tap an element to ask about it or change its text." else "This is the app on Kiln's hidden screen — tap and swipe to use it.",
                style = T.label)
            status?.let { Text(it, style = T.bodySmall.copy(color = if (it.startsWith("Updated")) N.ok else N.warn)) }
            val f = frame
            val (w, h) = disp
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (f == null || w == 0 || h == 0) Text("Waiting for the app to draw…", style = T.bodySmall, modifier = Modifier.padding(32.dp))
                else Box(Modifier.heightIn(max = 520.dp).aspectRatio(w.toFloat() / h, matchHeightConstraintsFirst = true)
                    .clip(N.shapeMd).border(1.dp, if (select) N.accent else N.cardRing, N.shapeMd)
                    .pointerInput(select, w, h) {
                        detectTapGestures { o ->
                            val x = (o.x * w / size.width).toInt(); val y = (o.y * h / size.height).toInt()
                            scope.launch {
                                if (select) { busy = true; picked = vm.previewPick(name, x, y); busy = false }
                                else vm.previewDevice.tap(x, y)
                            }
                        }
                    }
                    .pointerInput(select, w, h) {
                        if (select) return@pointerInput
                        var start = Offset.Zero; var end = Offset.Zero
                        detectDragGestures(onDragStart = { start = it; end = it }, onDrag = { c, d -> c.consume(); end += d },
                            onDragEnd = {
                                val k = w.toFloat() / size.width
                                scope.launch { vm.previewDevice.swipe((start.x * k).toInt(), (start.y * k).toInt(), (end.x * k).toInt(), (end.y * k).toInt(), 250) }
                            })
                    }) {
                    Image(f, "The app's screen", Modifier.fillMaxSize())
                    picked?.node?.let { n ->
                        Canvas(Modifier.fillMaxSize()) {
                            val k = size.width / w
                            drawRect(N.accent, Offset(n.left * k, n.top * k), Size((n.right - n.left) * k, (n.bottom - n.top) * k), style = Stroke(2.dp.toPx()))
                        }
                    }
                }
            }
            if (busy) Text("Finding that element…", style = T.label)
            picked?.let { p ->
                PickedCard(p, onAsk = { onAsk(describe(p)) }, onEdit = { hit, new ->
                    scope.launch {
                        busy = true; status = "Rebuilding…"
                        status = vm.quickEditText(name, hit, p.node.text, new) ?: "Updated — no AI used"
                        busy = false; picked = null
                    }
                })
            }
        }
    }
}

@Composable
private fun PickedCard(p: Picked, onAsk: () -> Unit, onEdit: (SourceHit, String) -> Unit) {
    var editing by remember(p) { mutableStateOf(false) }
    var text by remember(p) { mutableStateOf(p.node.text) }
    // Text can be changed directly only when exactly one source line holds it.
    val editable = p.node.text.isNotBlank() && p.sources.size == 1
    Column(Modifier.fillMaxWidth().vCard(N.shapeLg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(p.label, style = T.subtitle)
        Text(p.node.cls.substringAfterLast('.') + if (p.node.clickable) " · tappable" else "", style = T.label)
        p.sources.forEach { Text("${it.path}:${it.line}  ${it.code}", style = T.monoSmall, maxLines = 1) }
        if (p.sources.isEmpty() && p.node.text.isNotBlank()) Text("Its text isn't a literal in the source (built at runtime or from data).", style = T.label)
        if (editing) {
            KField("", text, { text = it }, hint = "New text")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KButton("Cancel") { editing = false }
                KButton("Save and rebuild", Tone.Accent) { if (text.isNotBlank() && text != p.node.text) onEdit(p.sources.first(), text) }
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KButton("Ask Kiln about this", Tone.Accent, onClick = onAsk)
            if (editable) KButton("Edit text") { editing = true }
        }
    }
}

private fun describe(p: Picked): String {
    val n = p.node
    val what = n.cls.substringAfterLast('.')
    val label = p.label
    return buildString {
        append("[Selected on screen: the \"").append(label).append("\" ").append(what)
        append(" at ").append(n.left).append(',').append(n.top).append('–').append(n.right).append(',').append(n.bottom)
        p.sources.firstOrNull()?.let { append(" · ").append(it.path).append(':').append(it.line) }
        append("] ")
    }
}
