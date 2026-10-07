package app.kiln.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kiln.agent.Activity
import app.kiln.agent.AgentLoop
import app.kiln.core.parseJson
import app.kiln.core.str
import app.kiln.llm.ThinkTags
import app.kiln.ui.theme.N
import app.kiln.ui.theme.T
import app.kiln.ui.theme.vCard
import app.kiln.ui.theme.vInset
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/** What the chat list renders: messages, plus runs of agent work collapsed into one step group. */
private sealed interface Row_ { val key: String }
private data class Msg_(val a: Activity) : Row_ { override val key get() = "m${a.id}" }
private data class Steps_(val items: List<Activity>) : Row_ { override val key get() = "s${items.first().id}" }

private fun group(feed: List<Activity>): List<Row_> {
    val out = mutableListOf<Row_>()
    var run = mutableListOf<Activity>()
    fun flush() { if (run.isNotEmpty()) { out += Steps_(run); run = mutableListOf() } }
    for (a0 in feed) {
        // Sessions saved before the adapters split inline <think>…</think> still carry it in the reply text.
        val (thought, a) = splitThought(a0)
        if (thought != null) run += thought
        when (a.kind) {
            Activity.Kind.TOOL, Activity.Kind.THINKING -> run += a
            Activity.Kind.ASSISTANT -> if (a.text.isNotBlank()) { flush(); out += Msg_(a) }
            else -> { flush(); out += Msg_(a) }
        }
    }
    flush()
    return out
}

/** An ASSISTANT line with inline `<think>` → (THINKING line, reply without it); anything else passes through. */
private fun splitThought(a: Activity): Pair<Activity?, Activity> {
    if (a.kind != Activity.Kind.ASSISTANT || "<think>" !in a.text) return null to a
    val think = StringBuilder(); val reply = StringBuilder()
    ThinkTags().apply { feed(a.text) { t, inside -> (if (inside) think else reply).append(t) }; flush { t, inside -> (if (inside) think else reply).append(t) } }
    val thought = think.toString().trim().takeIf { it.isNotEmpty() }
        ?.let { Activity(id = -a.id - 1, kind = Activity.Kind.THINKING, text = it, status = a.status) }
    return thought to a.copy(text = reply.toString())
}

@Composable
fun ChatTab(vm: KilnVM, ps: ProjectState) {
    val loop by ps.loop.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        val l = loop
        val feed = l?.feed?.collectAsStateWithLifecycle()?.value ?: emptyList()
        val running = l?.running?.collectAsStateWithLifecycle()?.value ?: false
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (feed.isEmpty()) ChatEmpty(ps) { vm.send(ps.project.name, it) }
            else ChatList(l!!, feed, running)
        }
        if (l != null) Prompts(l)
        Composer(running, hint = if (feed.isEmpty()) "Describe what to build…" else "Ask for a change…",
            onSend = { vm.send(ps.project.name, it) }, onStop = { vm.stop(ps.project.name) })
    }
}

@Composable
private fun ChatEmpty(ps: ProjectState, onPick: (String) -> Unit) {
    val ideas = listOf("Polish the layout and spacing", "Add a settings screen", "Save data across restarts",
        "Add a second tab", "Show a notification", "Fix anything that looks off")
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        AppTile(ps.label, ps.project.name, 64.dp)
        Spacer(Modifier.height(14.dp))
        Text(ps.label, style = T.h4)
        Spacer(Modifier.height(6.dp))
        Text("Tell Kiln what to build or change. It writes the code, builds it here, installs it and checks it runs.",
            style = T.bodySmall.copy(color = N.textMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center))
        Spacer(Modifier.height(20.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ideas.forEach { KChip(it, false) { onPick(it) } }
        }
    }
}

@Composable
private fun ChatList(loop: AgentLoop, feed: List<Activity>, running: Boolean) {
    val rows = remember(feed) { group(feed) }
    val todos by loop.todos.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    // Follow the stream only while the user is at the bottom.
    val atEnd = !list.canScrollForward
    LaunchedEffect(rows.size, feed.lastOrNull()?.text?.length, feed.lastOrNull()?.status) {
        if (rows.isNotEmpty() && (atEnd || running)) list.animateScrollToItem(rows.size - 1, Int.MAX_VALUE / 2)
    }
    Column(Modifier.fillMaxSize()) {
        if (todos.isNotEmpty()) PlanCard(todos)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(rows, key = { it.key }) { r ->
                when (r) {
                    is Msg_ -> Message(r.a)
                    is Steps_ -> StepGroup(r.items, live = running && r === rows.last())
                }
            }
            if (running && rows.lastOrNull() !is Steps_) item(key = "working") { Working() }
        }
    }
}

@Composable
private fun Message(a: Activity) {
    when (a.kind) {
        Activity.Kind.USER -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(a.text, style = T.body.copy(color = N.accent100),
                modifier = Modifier.widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp))
                    .background(N.accent800).padding(horizontal = 14.dp, vertical = 10.dp))
        }
        Activity.Kind.ASSISTANT -> Markdown(a.text.trim(), Modifier.fillMaxWidth())
        Activity.Kind.NOTICE -> Banner(Icons.Rounded.Warning, a.text, N.warn)
        Activity.Kind.ERROR -> Banner(Icons.Rounded.ErrorOutline, a.text, N.danger)
        else -> {}
    }
}

@Composable
private fun Banner(icon: ImageVector, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().clip(N.shapeMd).background(color.copy(alpha = 0.10f)).border(1.dp, color.copy(alpha = 0.35f), N.shapeMd)
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = T.bodySmall.copy(color = color))
    }
}

@Composable
private fun Working() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        BrainIcon(live = true)
        Spacer(Modifier.width(8.dp))
        ShimmerText("Thinking")
    }
}

/** The pinned plan from the agent's todo tool. */
@Composable
private fun PlanCard(todos: List<app.kiln.tools.SessionState.Todo>) {
    var open by remember { mutableStateOf(false) }
    val done = todos.count { it.status == "done" }
    val current = todos.firstOrNull { it.status == "in_progress" } ?: todos.firstOrNull { it.status != "done" }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().vCard(N.shapeLg).clickable { open = !open }
        .animateContentSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Checklist, null, tint = N.accent2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(if (open || current == null) "Plan" else current.text, style = T.subtitle.copy(fontSize = 14.sp_()), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("$done/${todos.size}", style = T.monoSmall.copy(color = N.textLabel))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = N.textMuted)
        }
        LinearProgressIndicator(progress = { done / todos.size.toFloat() }, color = N.accent, trackColor = N.neutral800,
            drawStopIndicator = {}, modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)))
        if (open) Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            todos.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (t.status) {
                        "done" -> Icon(Icons.Rounded.CheckCircle, null, tint = N.ok, modifier = Modifier.size(16.dp))
                        "in_progress" -> CircularProgressIndicator(Modifier.size(14.dp), color = N.accent, strokeWidth = 2.dp)
                        else -> Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).border(1.5.dp, N.neutral700, RoundedCornerShape(7.dp)))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(t.text, style = T.bodySmall.copy(color = if (t.status == "done") N.textMuted else N.text))
                }
            }
        }
    }
}

private fun Int.sp_() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

/** Icon + one-line human title for a tool call. */
private fun describe(a: Activity): Pair<ImageVector, String> {
    if (a.kind == Activity.Kind.THINKING) return Icons.Rounded.Psychology to "Thought"
    val input = runCatching { parseJson(a.input ?: "{}") as JsonObject }.getOrNull()
    fun arg(k: String) = input?.str(k)?.let { if (k == "path") it.substringAfterLast('/') else it }
    return when (a.tool) {
        "write_file" -> Icons.Rounded.EditNote to "Wrote ${arg("path") ?: "a file"}"
        "edit_file", "multi_edit" -> Icons.Rounded.EditNote to "Edited ${arg("path") ?: "a file"}"
        "read_file" -> Icons.Rounded.Description to "Read ${arg("path") ?: "a file"}"
        "list_dir", "glob" -> Icons.Rounded.FolderOpen to "Listed files"
        "grep" -> Icons.Rounded.Search to "Searched for ${arg("pattern")?.let { "“$it”" } ?: "text"}"
        "move" -> Icons.Rounded.FolderOpen to "Moved ${arg("from") ?: "a file"}"
        "delete" -> Icons.Rounded.FolderOpen to "Deleted ${arg("path") ?: "a file"}"
        "check" -> Icons.Rounded.Build to "Type-checked"
        "build" -> Icons.Rounded.Build to "Built the app"
        "clean" -> Icons.Rounded.Build to "Cleaned the build"
        "install" -> Icons.Rounded.PhoneAndroid to "Installed"
        "run_app" -> Icons.Rounded.PlayArrow to "Built, installed and ran"
        "launch" -> Icons.Rounded.PlayArrow to "Launched the app"
        "stop_app" -> Icons.Rounded.Stop to "Stopped the app"
        "clear_data" -> Icons.Rounded.PhoneAndroid to "Cleared app data"
        "grant_permission" -> Icons.Rounded.PhoneAndroid to "Granted ${arg("permission") ?: "a permission"}"
        "logcat" -> Icons.Rounded.ReceiptLong to "Read logs"
        "last_crash" -> Icons.Rounded.ReceiptLong to "Checked for crashes"
        "screenshot" -> Icons.Rounded.PhoneAndroid to "Took a screenshot"
        "ui_tree" -> Icons.Rounded.PhoneAndroid to "Inspected the screen"
        "tap" -> Icons.Rounded.TouchApp to "Tapped ${arg("target") ?: ""}".trim()
        "type_text" -> Icons.Rounded.TouchApp to "Typed text"
        "swipe" -> Icons.Rounded.TouchApp to "Swiped"
        "press_key" -> Icons.Rounded.TouchApp to "Pressed ${arg("key") ?: "a key"}"
        "wait_for" -> Icons.Rounded.TouchApp to "Waited for ${arg("target") ?: "the screen"}"
        "shell", "dumpsys" -> Icons.Rounded.Terminal to (arg("command")?.take(48)?.let { "Ran $it" } ?: "Ran a command")
        "sdk_lookup" -> Icons.Rounded.MenuBook to "Looked up ${arg("query") ?: arg("name") ?: "the SDK"}"
        "kit_docs" -> Icons.Rounded.MenuBook to "Read the kit docs"
        "web_fetch" -> Icons.Rounded.Language to "Fetched ${arg("url")?.removePrefix("https://")?.take(40) ?: "a page"}"
        "todo" -> Icons.Rounded.Checklist to "Updated the plan"
        "subagent" -> Icons.Rounded.AutoAwesome to "Delegated a task"
        else -> Icons.Rounded.Build to (a.tool ?: "Step").replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

/** The model's reasoning: shimmers and streams its latest lines while live, then settles into a card you can open. */
@Composable
private fun ThoughtCard(a: Activity, live: Boolean) {
    var open by remember { mutableStateOf(false) }
    val thinking = live && a.status == Activity.Status.RUNNING
    val shape = RoundedCornerShape(14.dp)
    val ring by animateColorAsState(N.accent.copy(alpha = if (thinking) 0.55f else 0.18f), tween(400), label = "ring")
    val body = a.text.trim()
    Column(Modifier.fillMaxWidth().clip(shape)
        .background(Brush.horizontalGradient(listOf(N.accent.copy(alpha = 0.12f), N.surface.copy(alpha = 0.30f))))
        .border(1.dp, ring, shape)
        .clickable(enabled = !thinking && body.isNotEmpty()) { open = !open }
        .animateContentSize()
        .padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrainIcon(thinking)
            Spacer(Modifier.width(8.dp))
            if (thinking) ShimmerText("Thinking", Modifier.weight(1f))
            else Text(if (a.ms >= 100) "Thought for %.1fs".format(a.ms / 1000.0) else "Thought",
                style = T.bodySmall.copy(color = N.textLabel, fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
            if (!thinking && body.isNotEmpty())
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = N.textMuted, modifier = Modifier.size(18.dp))
        }
        if (body.isEmpty()) return@Column
        val rail = N.accent.copy(alpha = 0.5f)
        val quote = Modifier.padding(top = 8.dp).fillMaxWidth()
            .drawBehind { drawRoundRect(rail, size = Size(2.dp.toPx(), size.height), cornerRadius = CornerRadius(1.dp.toPx())) }
            .padding(start = 12.dp)
        val italic = T.bodySmall.copy(color = N.textMuted, fontStyle = FontStyle.Italic)
        when {
            open -> Markdown(body, quote.graphicsLayer(alpha = 0.82f))
            // Live: the newest lines, so you can watch it reason without the card growing without bound.
            thinking -> Text(tail(body, 240), style = italic, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = quote)
            else -> Text(body.replace(Regex("\\s+"), " "), style = italic, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = quote)
        }
    }
}

/** The last [n] characters, cut at a word, with a leading ellipsis when trimmed. */
private fun tail(s: String, n: Int): String {
    val flat = s.replace(Regex("\\s+"), " ")
    if (flat.length <= n) return flat
    val cut = flat.substring(flat.length - n)
    return "…" + cut.substring((cut.indexOf(' ') + 1).coerceAtMost(cut.length))
}

@Composable
private fun BrainIcon(live: Boolean) {
    val t = rememberInfiniteTransition(label = "brain")
    val s by t.animateFloat(0.86f, 1.12f, infiniteRepeatable(tween(750, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale")
    Icon(Icons.Rounded.Psychology, null, tint = if (live) N.accent2 else N.accent2.copy(alpha = 0.75f),
        modifier = Modifier.size(16.dp).graphicsLayer { if (live) { scaleX = s; scaleY = s } })
}

/** A label with a light sweeping across it. */
@Composable
private fun ShimmerText(text: String, modifier: Modifier = Modifier) {
    val x by rememberInfiniteTransition(label = "shimmer")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "x")
    val w = 160f
    val brush = Brush.linearGradient(listOf(N.textMuted, N.accent2, N.text, N.accent2, N.textMuted),
        start = Offset(-w + x * 3 * w, 0f), end = Offset(x * 3 * w, 0f))
    Text("$text…", style = T.bodySmall.copy(brush = brush, fontWeight = FontWeight.Medium), modifier = modifier)
}

/** A run of agent steps: live while working, then a calm one-line summary you can open. */
@Composable
private fun StepGroup(items: List<Activity>, live: Boolean) {
    // Reasoning on its own (no tool calls) is shown as thought cards, not a "Thought" summary line.
    if (items.all { it.kind == Activity.Kind.THINKING }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { items.forEach { ThoughtCard(it, live) } }
        return
    }
    var open by remember { mutableStateOf(false) }
    val tools = items.filter { it.kind == Activity.Kind.TOOL }
    val failed = tools.count { it.status == Activity.Status.FAILED }
    val current = items.lastOrNull { it.status == Activity.Status.RUNNING }
    val secs = items.sumOf { it.ms } / 1000.0
    val shot = items.lastOrNull { it.images.isNotEmpty() }?.images?.firstOrNull()
    Column(Modifier.fillMaxWidth()) {
        if (live && current?.kind == Activity.Kind.THINKING) ThoughtCard(current, live = true)
        else Row(Modifier.clip(N.shapeMd).clickable { open = !open }.padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (live && current != null) {
                CircularProgressIndicator(Modifier.size(14.dp), color = N.accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                val (_, title) = describe(current)
                Text(current.progress.ifBlank { "$title…" }, style = T.bodySmall.copy(color = N.textLabel), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            } else {
                Icon(if (failed > 0) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle, null,
                    tint = if (failed > 0) N.warn else N.ok.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                val n = tools.size
                Text(buildString {
                    append(if (n == 0) "Thought" else "$n step${if (n == 1) "" else "s"}")
                    if (secs >= 0.1) append(" · %.1fs".format(secs))
                    if (failed > 0) append(" · $failed failed")
                }, style = T.bodySmall.copy(color = N.textMuted))
            }
            Spacer(Modifier.width(4.dp))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = N.textMuted, modifier = Modifier.size(18.dp))
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 6.dp, top = 2.dp).fillMaxWidth()) {
                items.forEach { if (it.kind == Activity.Kind.THINKING) Box(Modifier.padding(vertical = 4.dp)) { ThoughtCard(it, live = false) } else StepRow(it) }
            }
        }
        if (shot != null) Screenshot(shot, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun StepRow(a: Activity) {
    var open by remember { mutableStateOf(false) }
    val (icon, title) = describe(a)
    val color = when (a.status) {
        Activity.Status.FAILED -> N.danger; Activity.Status.DENIED -> N.warn; else -> N.textLabel
    }
    Row(Modifier.fillMaxWidth()) {
        // Timeline rail.
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(22.dp)) {
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(N.surface), contentAlignment = Alignment.Center) {
                if (a.status == Activity.Status.RUNNING) CircularProgressIndicator(Modifier.size(12.dp), color = N.accent, strokeWidth = 1.5.dp)
                else Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
            }
            Box(Modifier.width(1.dp).height(if (open) 0.dp else 10.dp).background(N.divider))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).clip(N.shapeSm).clickable { open = !open }.padding(top = 2.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = T.bodySmall.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (a.ms > 0) Text("%.1fs".format(a.ms / 1000.0), style = T.monoSmall)
            }
            val detail = if (a.kind == Activity.Kind.THINKING) a.text else a.summary
            if (detail.isNotBlank()) Text(detail.trim(), style = T.label.copy(color = N.textMuted), maxLines = if (open) 40 else 1,
                overflow = TextOverflow.Ellipsis)
            if (open && !a.input.isNullOrBlank() && a.kind == Activity.Kind.TOOL)
                Text(a.input.take(2000), style = T.monoSmall, softWrap = false,
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth().vInset().horizontalScroll(rememberScrollState()).padding(8.dp))
        }
    }
}

@Composable
private fun Screenshot(png: ByteArray, modifier: Modifier = Modifier) {
    val bmp = remember(png) { BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap() } ?: return
    var full by remember { mutableStateOf(false) }
    Image(bmp, "Screenshot", contentScale = ContentScale.Fit,
        modifier = modifier.heightIn(max = 260.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, N.cardRing, RoundedCornerShape(14.dp))
            .clickable { full = true })
    if (full) Dialog({ full = false }, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(N.bg.copy(alpha = 0.96f)).clickable { full = false }, contentAlignment = Alignment.Center) {
            Image(bmp, "Screenshot", contentScale = ContentScale.Fit, modifier = Modifier.padding(24.dp).clip(RoundedCornerShape(18.dp)))
            IconBtn(Icons.Rounded.Close, "Close", Modifier.align(Alignment.TopEnd).padding(12.dp)) { full = false }
        }
    }
}

/** Approval and ask_user requests, inline above the composer instead of modal dialogs. */
@Composable
private fun Prompts(loop: AgentLoop) {
    val approval by loop.approval.collectAsStateWithLifecycle()
    val question by loop.question.collectAsStateWithLifecycle()
    approval?.let { a ->
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().vCard(N.shapeLg, N.warn.copy(alpha = 0.6f)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Allow ${a.tool.replace('_', ' ')}?", style = T.subtitle)
            Text(a.input.take(600), style = T.monoSmall.copy(color = N.textLabel), maxLines = 8, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().vInset().padding(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KButton("Deny", Tone.Danger) { loop.answerApproval(false) }
                Spacer(Modifier.weight(1f))
                KButton("Always") { loop.answerApproval(true, always = true) }
                KButton("Allow", Tone.Accent) { loop.answerApproval(true) }
            }
        }
    }
    question?.let { q ->
        var free by remember(q) { mutableStateOf("") }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().vCard(N.shapeLg, N.accent.copy(alpha = 0.6f)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(q.text, style = T.subtitle)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                q.options.forEach { o -> KChip(o, true) { loop.answerQuestion(o) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                KField("", free, { free = it }, Modifier.weight(1f), hint = "Or type an answer")
                Spacer(Modifier.width(8.dp))
                FilledIconBtn(Icons.Rounded.ArrowUpward, "Answer", enabled = free.isNotBlank()) { loop.answerQuestion(free.trim()) }
            }
        }
    }
}

@Composable
private fun Composer(running: Boolean, hint: String, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var started by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(running) {
        if (running) { started = System.currentTimeMillis(); while (true) { elapsed = (System.currentTimeMillis() - started) / 1000; delay(1000) } }
    }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp, top = 4.dp)) {
        if (running) Text("Working · ${elapsed / 60}:${"%02d".format(elapsed % 60)}", style = T.label.copy(color = N.accent2),
            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(N.surface)
            .border(1.dp, if (text.isNotEmpty()) N.accent.copy(alpha = 0.5f) else N.cardRing, RoundedCornerShape(26.dp))
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (text.isEmpty()) Text(hint, style = T.body.copy(color = N.textMuted))
                BasicTextField(text, { text = it }, textStyle = T.body, cursorBrush = SolidColor(N.accent),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp))
            }
            Spacer(Modifier.width(8.dp))
            if (running && text.isBlank()) FilledIconBtn(Icons.Rounded.Stop, "Stop", container = N.danger, onClick = onStop)
            else FilledIconBtn(Icons.Rounded.ArrowUpward, "Send", enabled = text.isNotBlank() && !running) { onSend(text.trim()); text = "" }
        }
    }
}
