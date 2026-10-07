package app.kiln.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kiln.agent.Activity
import app.kiln.agent.AgentLoop
import app.kiln.ui.theme.N
import app.kiln.ui.theme.T
import app.kiln.ui.theme.vCard
import app.kiln.ui.theme.vInset

@Composable
fun ChatTab(vm: KilnVM, loop: AgentLoop?) {
    Column(Modifier.fillMaxSize()) {
        if (loop == null) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Describe the app you want — Kiln writes it, builds it on this phone, installs it and checks it.",
                    style = T.body.copy(color = N.textMuted))
            }
        } else {
            val feed by loop.feed.collectAsStateWithLifecycle()
            val running by loop.running.collectAsStateWithLifecycle()
            val cost by loop.cost.collectAsStateWithLifecycle()
            val todos by loop.todos.collectAsStateWithLifecycle()
            val list = rememberLazyListState()
            LaunchedEffect(feed.size, feed.lastOrNull()?.text?.length) { if (feed.isNotEmpty()) list.animateScrollToItem(feed.size - 1) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(loop.session.meta.title.ifBlank { "New chat" }, style = T.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                if (running) { CircularProgressIndicator(Modifier.size(14.dp), color = N.accent, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text("$" + "%.3f".format(cost), style = T.monoSmall)
            }
            if (todos.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).vInset().padding(10.dp)) {
                todos.forEach { t ->
                    Text((when (t.status) { "done" -> "✓ "; "in_progress" -> "▸ "; else -> "· " }) + t.text,
                        style = T.bodySmall.copy(color = if (t.status == "done") N.textMuted else N.text))
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(feed, key = { it.id }) { ActivityRow(it) }
            }
            Dialogs(loop)
        }
        InputBar(vm, loop)
    }
}

@Composable
private fun ActivityRow(a: Activity) {
    when (a.kind) {
        Activity.Kind.USER -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(a.text, style = T.body, modifier = Modifier.clip(N.shapeLg).background(N.accent800).padding(12.dp))
        }
        Activity.Kind.ASSISTANT -> Text(a.text, style = T.body)
        Activity.Kind.THINKING -> {
            var open by remember { mutableStateOf(false) }
            Text(if (open) a.text else "thinking… " + a.text.takeLast(80).replace('\n', ' '),
                style = T.bodySmall.copy(color = N.textMuted), maxLines = if (open) 200 else 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { open = !open })
        }
        Activity.Kind.NOTICE -> Text(a.text, style = T.label.copy(color = N.warn))
        Activity.Kind.ERROR -> Text(a.text, style = T.bodySmall.copy(color = N.danger))
        Activity.Kind.TOOL -> ToolCard(a)
    }
}

@Composable
private fun ToolCard(a: Activity) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().vCard().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (a.status) {
                Activity.Status.RUNNING -> CircularProgressIndicator(Modifier.size(12.dp), color = N.accent, strokeWidth = 2.dp)
                Activity.Status.DONE -> Dot(N.ok)
                Activity.Status.FAILED -> Dot(N.danger)
                Activity.Status.DENIED -> Dot(N.warn)
            }
            Spacer(Modifier.width(10.dp))
            Text(a.tool ?: "", style = T.mono.copy(color = N.text))
            Spacer(Modifier.width(8.dp))
            Text(if (a.status == Activity.Status.RUNNING) a.progress else a.summary, style = T.bodySmall.copy(color = N.textMuted),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (a.ms > 0) Text("${a.ms / 100 / 10.0}s", style = T.monoSmall)
        }
        if (open && !a.input.isNullOrBlank()) Text(a.input.take(1500), style = T.monoSmall, modifier = Modifier.padding(top = 6.dp))
        a.images.firstOrNull()?.let { png ->
            val bmp = remember(png) { BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap() }
            if (bmp != null) Image(bmp, null, contentScale = ContentScale.Fit,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(max = if (open) 600.dp else 220.dp).clip(N.shapeMd))
        }
    }
}

@Composable
private fun Dialogs(loop: AgentLoop) {
    val approval by loop.approval.collectAsStateWithLifecycle()
    val question by loop.question.collectAsStateWithLifecycle()
    approval?.let { a ->
        AlertDialog(onDismissRequest = {}, containerColor = N.surface,
            title = { Text("Allow ${a.tool}?", style = T.cardTitle) },
            text = { Text(a.input.take(1200), style = T.mono) },
            confirmButton = { Row {
                TextButton(onClick = { loop.answerApproval(true, always = true) }) { Text("Always (this chat)", color = N.textLabel) }
                TextButton(onClick = { loop.answerApproval(true) }) { Text("Allow", color = N.accent) }
            } },
            dismissButton = { TextButton(onClick = { loop.answerApproval(false) }) { Text("Deny", color = N.danger) } })
    }
    question?.let { q ->
        var free by remember(q) { mutableStateOf("") }
        AlertDialog(onDismissRequest = {}, containerColor = N.surface,
            title = { Text(q.text, style = T.cardTitle) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                q.options.forEach { o -> KButton(o, Tone.Accent, Modifier.fillMaxWidth()) { loop.answerQuestion(o) } }
                KField("Or answer", free, { free = it })
            } },
            confirmButton = { TextButton(onClick = { loop.answerQuestion(free.ifBlank { "no preference" }) }) { Text("Send", color = N.accent) } })
    }
}

@Composable
private fun InputBar(vm: KilnVM, loop: AgentLoop?) {
    var text by remember { mutableStateOf("") }
    val running = loop?.running?.collectAsStateWithLifecycle()?.value ?: false
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f).vInset().padding(horizontal = 12.dp, vertical = 12.dp)) {
            if (text.isEmpty()) Text(if (loop == null) "Describe an app…" else "Ask for a change…", style = T.body.copy(color = N.textMuted))
            BasicTextField(text, { text = it }, textStyle = T.body, cursorBrush = SolidColor(N.accent),
                modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp))
        }
        Spacer(Modifier.width(8.dp))
        if (running) KButton("Stop", Tone.Danger) { vm.stop() }
        else KButton("Send", Tone.Accent, enabled = text.isNotBlank()) { vm.send(text.trim()); text = "" }
    }
}
