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
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.AttachFile
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
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Mic
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

/** A small label for work a helper agent did ("QA"). */
@Composable
private fun AgentTag(label: String) {
    Text(label, style = T.label.copy(color = N.accent100, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(N.accent800).padding(horizontal = 6.dp, vertical = 1.dp))
}

/** [feed] with each helper agent's activities right after the tool call that ran it, tagged with who did them. */
private fun withHelperSteps(feed: List<Activity>): List<Activity> = feed.flatMap { a ->
    if (a.children.isEmpty()) listOf(a)
    else listOf(a) + a.children.map { c ->
        // Negative ids can't collide with the main feed's (list keys).
        c.copy(id = -(a.id * 100_000 + c.id + 1), agent = c.agent ?: if (a.tool == "qa_check") "QA" else "Helper")
    }
}

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
            val loading by ps.loading.collectAsStateWithLifecycle()
            if (l == null && loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = N.accent, strokeWidth = 2.dp)
            }
            else if (feed.isEmpty()) ChatEmpty(ps) { vm.send(ps.project.name, it) }
            else ChatList(vm, ps, l!!, feed, running)
        }
        RuleProposals(vm, ps, feed)
        AttemptsBar(vm, ps)
        if (l != null) Prompts(l)
        if (l != null && !running) PlanReady(l) { criteria ->
            vm.send(ps.project.name, "Build the approved plan above. When it's built, verify every done criterion on the device.",
                goal = criteria)
        }
        var planFirst by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        var untilVerified by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        var tryThree by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        if (l != null) Spend(l)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KChip("Plan first", planFirst) { planFirst = !planFirst; if (planFirst) untilVerified = false }
            KChip("Keep going until verified", untilVerified) { untilVerified = !untilVerified; if (untilVerified) planFirst = false }
            KChip("Try 3 ways", tryThree) { tryThree = !tryThree; if (tryThree) planFirst = false }
        }
        val stopping by ps.stopping.collectAsStateWithLifecycle()
        Composer(running, stopping, l?.startedAt ?: 0L, ps.draft, ps.attachments, hint = when {
                running -> "Add to what it's doing…"
                planFirst -> "Describe the app — you'll get a plan first"
                feed.isEmpty() -> "Describe what to build…"
                else -> "Ask for a change…" },
            onSend = { t, files ->
                if (tryThree && !running && t.isNotBlank() && files.isEmpty()) { vm.bestOf(ps.project.name, t, 3); tryThree = false; return@Composer }
                vm.send(ps.project.name, t, files,
                    mode = if (planFirst) app.kiln.agent.AgentLoop.Mode.PLAN else app.kiln.agent.AgentLoop.Mode.BUILD,
                    goal = if (untilVerified) "The request below works on the device, verified with run_app / ui_tree / tap:\n$t" else null)
            }, onStop = { vm.stop(ps.project.name) })
    }
}

@Composable
private fun ChatEmpty(ps: ProjectState, onPick: (String) -> Unit) {
    val ideas = listOf("Polish the layout and spacing", "Add a settings screen", "Save data across restarts",
        "Add a second tab", "Show a notification", "Fix anything that looks off")
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        ProjectIcon(ps.project, ps.label, 64.dp)
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
private fun ChatList(vm: KilnVM, ps: ProjectState, loop: AgentLoop, feed: List<Activity>, running: Boolean) {
    var review by remember { mutableStateOf<Int?>(null) }
    // Lists the snapshot folder: read off the main thread.
    val snapshots by androidx.compose.runtime.produceState(emptySet<Int>(), loop, feed.size, running) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.kiln.agent.Turns.indexes(loop.session).toSet() } }
    review?.let { ChangesSheet(vm, ps, it) { review = null } }
    // A helper agent's steps (qa_check) follow the call that started them, as ordinary steps with a tag.
    val rows = remember(feed) { group(withHelperSteps(feed)) }
    val todos by loop.todos.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    // Follow the stream only while the user is at the bottom.
    // Follow the stream only while the reader is at the bottom; scrolling up to read must stick.
    val atEnd by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { !list.canScrollForward } }
    // A chat opens at its latest message, not at the top of a long conversation.
    LaunchedEffect(loop) { if (rows.isNotEmpty()) list.scrollToItem(rows.size - 1, Int.MAX_VALUE / 2) }
    LaunchedEffect(rows.size, feed.lastOrNull()?.text?.length, feed.lastOrNull()?.status) {
        if (rows.isNotEmpty() && atEnd) list.animateScrollToItem(rows.size - 1, Int.MAX_VALUE / 2)
    }
    Column(Modifier.fillMaxSize()) {
        if (todos.isNotEmpty()) PlanCard(todos)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(rows, key = { it.key }) { r ->
                when (r) {
                    is Msg_ -> Message(r.a, r.a.msgIndex in snapshots, if (running || r.a.msgIndex < 0) null else { action ->
                        when (action) {
                            TurnAction.REWIND -> vm.rewind(ps.project.name, r.a.msgIndex)
                            TurnAction.FORK -> vm.fork(ps.project.name, r.a.msgIndex)
                            TurnAction.CHANGES -> review = r.a.msgIndex
                        }
                    }, onContinue = if (!running && r === rows.last() && (r.a.kind == Activity.Kind.ERROR || r.a.text.startsWith("Stopped") || r.a.text.startsWith("Interrupted")))
                        { { vm.send(ps.project.name, "Continue where you left off.") } } else null)
                    is Steps_ -> StepGroup(r.items, live = running && r === rows.last())
                }
            }
            if (running && rows.lastOrNull() !is Steps_) item(key = "working") { Working() }
        }
    }
}

private enum class TurnAction { REWIND, FORK, CHANGES }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Message(a: Activity, hasSnapshot: Boolean = false, onAction: ((TurnAction) -> Unit)? = null, onContinue: (() -> Unit)? = null) {
    when (a.kind) {
        Activity.Kind.USER -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Attached photos above the bubble, files as chips; tap a photo to view it full screen.
            if (a.images.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { a.images.forEach { Screenshot(it, Modifier.heightIn(max = 160.dp)) } }
            if (a.files.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                a.files.forEach { AttachmentChip(it, null) }
            }
            var menu by remember { mutableStateOf(false) }
            if (a.text.isNotBlank()) Box {
                Text(a.text, style = T.body.copy(color = N.accent100),
                    modifier = Modifier.widthIn(max = 320.dp)
                        .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp))
                        .then(if (onAction != null) Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true }) else Modifier)
                        .background(N.accent800).padding(horizontal = 14.dp, vertical = 10.dp))
                if (onAction != null) androidx.compose.material3.DropdownMenu(menu, { menu = false }, containerColor = N.surface) {
                    (if (hasSnapshot) listOf(TurnAction.CHANGES to "Changes in this turn", TurnAction.REWIND to "Rewind to here") else emptyList())
                        .plus(TurnAction.FORK to "Fork chat from here").forEach { (act, label) ->
                        androidx.compose.material3.DropdownMenuItem({ Text(label, style = T.body) }, { menu = false; onAction(act) })
                    }
                }
            }
        }
        Activity.Kind.ASSISTANT -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            a.agent?.let { AgentTag(it) }
            Markdown(a.text.trim(), Modifier.fillMaxWidth())
        }
        Activity.Kind.NOTICE -> Banner(Icons.Rounded.Warning, a.text, N.warn, onContinue?.let { "Continue" to it })
        Activity.Kind.ERROR -> Banner(Icons.Rounded.ErrorOutline, a.text, N.danger, onContinue?.let { "Retry" to it })
        else -> {}
    }
}

@Composable
private fun Banner(icon: ImageVector, text: String, color: androidx.compose.ui.graphics.Color, action: Pair<String, () -> Unit>? = null) {
    Row(Modifier.fillMaxWidth().clip(N.shapeMd).background(color.copy(alpha = 0.10f)).border(1.dp, color.copy(alpha = 0.35f), N.shapeMd)
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = if (action != null) Alignment.CenterVertically else Alignment.Top) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = T.bodySmall.copy(color = color), modifier = Modifier.weight(1f))
        action?.let { (label, go) -> Spacer(Modifier.width(8.dp)); KButton(label, onClick = go) }
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

/** Tools whose "error" is a finding (compile errors, a crash found), not a broken step. */
private val REPORTING_TOOLS = setOf("check", "build", "run_app", "last_crash")

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
        "logcat" -> Icons.AutoMirrored.Rounded.ReceiptLong to "Read logs"
        "last_crash" -> Icons.AutoMirrored.Rounded.ReceiptLong to "Checked for crashes"
        "screenshot" -> Icons.Rounded.PhoneAndroid to "Took a screenshot"
        "ui_tree" -> Icons.Rounded.PhoneAndroid to "Inspected the screen"
        "tap" -> Icons.Rounded.TouchApp to "Tapped ${arg("target") ?: ""}".trim()
        "type_text" -> Icons.Rounded.TouchApp to "Typed text"
        "swipe" -> Icons.Rounded.TouchApp to "Swiped"
        "press_key" -> Icons.Rounded.TouchApp to "Pressed ${arg("key") ?: "a key"}"
        "wait_for" -> Icons.Rounded.TouchApp to "Waited for ${arg("target") ?: "the screen"}"
        "shell", "dumpsys" -> Icons.Rounded.Terminal to (arg("command")?.take(48)?.let { "Ran $it" } ?: "Ran a command")
        "sdk_lookup" -> Icons.AutoMirrored.Rounded.MenuBook to "Looked up ${arg("query") ?: arg("name") ?: "the SDK"}"
        "kit_search" -> Icons.AutoMirrored.Rounded.MenuBook to "Searched the kit"
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
            a.agent?.let { AgentTag(it); Spacer(Modifier.width(6.dp)) }
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
    // Only a live thought pulses: an infinite transition per finished card kept the frame clock running.
    if (!live) { Icon(Icons.Rounded.Psychology, null, tint = N.accent2.copy(alpha = 0.75f), modifier = Modifier.size(16.dp)); return }
    val t = rememberInfiniteTransition(label = "brain")
    val s by t.animateFloat(0.86f, 1.12f, infiniteRepeatable(tween(750, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale")
    Icon(Icons.Rounded.Psychology, null, tint = N.accent2, modifier = Modifier.size(16.dp).graphicsLayer { scaleX = s; scaleY = s })
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
    // A check/build that reports compile errors did its job; only steps that broke count as failed.
    val buildErrors = tools.count { it.status == Activity.Status.FAILED && it.tool in REPORTING_TOOLS }
    val failed = tools.count { it.status == Activity.Status.FAILED } - buildErrors
    val stopped = tools.count { it.status == Activity.Status.STOPPED }
    val current = items.lastOrNull { it.status == Activity.Status.RUNNING }
    val secs = items.sumOf { it.ms } / 1000.0
    // The newest picture of the screen in this group (a step's own preview or its result image).
    val shot = items.lastOrNull { it.preview != null || it.images.isNotEmpty() }?.let { it.preview ?: it.images.first() }
    if (live) {
        // While it works, show each step as it finishes (latest few, the rest one tap away), then what's running now.
        val done = items.filter { it !== current && !(it.kind == Activity.Kind.THINKING && it.text.isBlank()) }
        val shown = if (open) done else done.takeLast(4)
        Column(Modifier.fillMaxWidth().animateContentSize()) {
            if (done.size > shown.size) Text("+${done.size - shown.size} earlier step${if (done.size - shown.size == 1) "" else "s"}",
                style = T.bodySmall.copy(color = N.textMuted), modifier = Modifier.clip(N.shapeMd).clickable { open = true }.padding(vertical = 4.dp))
            shown.forEach { if (it.kind == Activity.Kind.THINKING) Box(Modifier.padding(vertical = 4.dp)) { ThoughtCard(it, live = false) } else StepRow(it) }
            when {
                current?.kind == Activity.Kind.THINKING -> ThoughtCard(current, live = true)
                current != null -> Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = N.accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    val (_, title) = describe(current)
                    Text(current.progress.ifBlank { "$title…" }, style = T.bodySmall.copy(color = N.textLabel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // What the agent last saw on the device — unless a step shown above already carries that picture.
            val onStep = shown.any { (it.preview ?: it.images.firstOrNull()) === shot }
            if (shot != null && !onStep) Screenshot(shot, Modifier.padding(top = 8.dp))
        }
        return
    }
    Column(Modifier.fillMaxWidth()) {
        if (live && current?.kind == Activity.Kind.THINKING) ThoughtCard(current, live = true)
        else Row(Modifier.clip(N.shapeMd).clickable { open = !open }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (live && current != null) {
                CircularProgressIndicator(Modifier.size(14.dp), color = N.accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                val (_, title) = describe(current)
                Text(current.progress.ifBlank { "$title…" }, style = T.bodySmall.copy(color = N.textLabel), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            } else {
                Icon(if (failed + buildErrors > 0) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle, null,
                    tint = if (failed + buildErrors > 0) N.warn else N.ok.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                val n = tools.size
                Text(buildString {
                    append(if (n == 0) "Thought" else "$n step${if (n == 1) "" else "s"}")
                    if (secs >= 0.1) append(" · %.1fs".format(secs))
                    if (buildErrors > 0) append(" · build had errors")
                    if (failed > 0) append(" · $failed failed")
                    if (stopped > 0) append(" · stopped")
                }, style = T.bodySmall.copy(color = N.textMuted))
            }
            Spacer(Modifier.width(4.dp))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = N.textMuted, modifier = Modifier.size(18.dp))
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 2.dp).fillMaxWidth()) {
                items.forEach { if (it.kind == Activity.Kind.THINKING) Box(Modifier.padding(vertical = 4.dp)) { ThoughtCard(it, live = false) } else StepRow(it) }
            }
        }
        // Collapsed: the latest screen stands for the group. Opened: each step shows its own, so not again here.
        if (shot != null && !open) Screenshot(shot, Modifier.padding(top = 8.dp))
        items.lastOrNull { it.video != null }?.video?.let { QaVideo(it, Modifier.padding(top = 8.dp).fillMaxWidth()) }
    }
}

@Composable
private fun StepRow(a: Activity) {
    var open by remember { mutableStateOf(false) }
    val (icon, title) = describe(a)
    val color = when (a.status) {
        Activity.Status.FAILED -> if (a.tool in REPORTING_TOOLS) N.warn else N.danger
        Activity.Status.DENIED -> N.warn; else -> N.textLabel
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
                a.agent?.let { AgentTag(it); Spacer(Modifier.width(6.dp)) }
                Text(title, style = T.bodySmall.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (a.ms >= 100) Text("%.1fs".format(a.ms / 1000.0), style = T.monoSmall)
            }
            val detail = if (a.kind == Activity.Kind.THINKING) a.text else a.summary
            if (detail.isNotBlank()) Text(detail.trim(), style = T.label.copy(color = N.textMuted), maxLines = if (open) 40 else 1,
                overflow = TextOverflow.Ellipsis)
            // What the screen looked like after this step (tap to enlarge).
            (a.preview ?: a.images.firstOrNull())?.let { Screenshot(it, Modifier.padding(top = 6.dp).heightIn(max = 180.dp)) }
            // A helper agent's step list, when its steps aren't shown inline (e.g. after reopening the chat).
            a.detail?.takeIf { it.isNotBlank() && a.children.isEmpty() }?.let { d ->
                if (open) Text(d, style = T.monoSmall.copy(color = N.textLabel), modifier = Modifier.padding(top = 6.dp).fillMaxWidth().vInset().padding(8.dp))
                else Text("${d.lines().size} QA steps · tap to see them", style = T.label.copy(color = N.accent2), maxLines = 1)
            }
            if (open && !a.input.isNullOrBlank() && a.kind == Activity.Kind.TOOL)
                Text(a.input.take(2000), style = T.monoSmall, softWrap = false,
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth().vInset().horizontalScroll(rememberScrollState()).padding(8.dp))
        }
    }
}

@Composable
private fun Screenshot(png: ByteArray, modifier: Modifier = Modifier) {
    val bmp = rememberDecoded(png, 900) ?: return
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
internal fun Prompts(loop: AgentLoop) {
    val approval by loop.approval.collectAsStateWithLifecycle()
    val question by loop.question.collectAsStateWithLifecycle()
    approval?.let { a ->
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().vCard(N.shapeLg, N.warn.copy(alpha = 0.6f)).padding(14.dp),
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
    question?.takeIf { it.kind == "look" }?.let { q -> LookPicker(q.text) { loop.answerQuestion(it) } }
    question?.takeIf { it.kind != "look" }?.let { q -> QuestionCard(q) { loop.answerQuestion(it) } }
}

@Composable
private fun Composer(running: Boolean, stopping: Boolean, runStartedAt: Long, draft: kotlinx.coroutines.flow.MutableStateFlow<String?>,
                     files: MutableList<app.kiln.agent.Attachment>, hint: String, onSend: (String, List<app.kiln.agent.Attachment>) -> Unit, onStop: () -> Unit) {
    // Survives rotation, and tab switches through the tab's saveable state; picked files live in the project's state.
    var text by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val pick = rememberAttachmentPicker { files += it }
    val pending by draft.collectAsStateWithLifecycle()
    LaunchedEffect(pending) { pending?.let { text = it + text; draft.value = null } }
    var menu by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Dictation through the system recogniser; the words land in the field to edit before sending.
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { said ->
            text = if (text.isBlank()) said else text.trimEnd() + " " + said
        }
    }
    var started by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(running) {
        // From the run's start, not this screen's: leaving and coming back keeps counting.
        if (running) { started = runStartedAt.takeIf { it > 0 } ?: System.currentTimeMillis(); while (true) { elapsed = (System.currentTimeMillis() - started) / 1000; delay(1000) } }
    }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp, top = 4.dp)) {
        if (running) Text(if (stopping) "Stopping…" else "Working · ${elapsed / 60}:${"%02d".format(elapsed % 60)}", style = T.label.copy(color = N.accent2),
            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
        if (files.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            files.forEach { f -> AttachmentChip(f.name, f.bytes.takeIf { f.mime.startsWith("image/") }) { files.remove(f) } }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(N.surface)
            .border(1.dp, if (text.isNotEmpty() || files.isNotEmpty()) N.accent.copy(alpha = 0.5f) else N.cardRing, RoundedCornerShape(26.dp))
            .padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
            Box {
                IconBtn(Icons.Rounded.AttachFile, "Attach") { menu = true }
                androidx.compose.material3.DropdownMenu(menu, { menu = false }, containerColor = N.surfaceHi) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Photo", style = T.body) },
                        leadingIcon = { Icon(Icons.Rounded.Image, null, tint = N.textLabel) }, onClick = { menu = false; pick.photo() })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("File", style = T.body) },
                        leadingIcon = { Icon(Icons.Rounded.Description, null, tint = N.textLabel) }, onClick = { menu = false; pick.file() })
                }
            }
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (text.isEmpty()) Text(hint, style = T.body.copy(color = N.textMuted))
                BasicTextField(text, { text = it }, textStyle = T.body, cursorBrush = SolidColor(N.accent),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).fieldLabel("Message"))
            }
            Spacer(Modifier.width(8.dp))
            // Stop shows whenever there's no text to send, files or not: kept files mustn't hide it mid-run.
            if (running && text.isBlank()) FilledIconBtn(Icons.Rounded.Stop, "Stop", container = N.danger, enabled = !stopping, onClick = onStop)
            else if (text.isBlank() && files.isEmpty()) FilledIconBtn(Icons.Rounded.Mic, "Speak") {
                runCatching {
                    voice.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say what to build or change"))
                }.onFailure { android.widget.Toast.makeText(ctx, "No speech recogniser on this phone", android.widget.Toast.LENGTH_SHORT).show() }
            }
            // While it works, sending steers the run (it reads the message at its next step).
            else FilledIconBtn(Icons.Rounded.ArrowUpward, "Send", enabled = text.isNotBlank() || (files.isNotEmpty() && !running)) {
                onSend(text.trim(), files.toList()); text = ""
                // A message sent mid-run can't carry files: keep them here for when the run finishes, don't drop them.
                if (!running) files.clear()
            }
        }
    }
}

/** After a Plan-mode reply: build it as a goal run whose done criteria come from the plan. */
@Composable
private fun PlanReady(loop: AgentLoop, onBuild: (String) -> Unit) {
    val plan by loop.plan.collectAsStateWithLifecycle()
    val p = plan ?: return
    val criteria = p.substringAfter("Done criteria", "").substringAfter('\n').trim().ifBlank { p.takeLast(1500) }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().vCard(N.shapeLg, N.accent.copy(alpha = 0.5f))
        .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Checklist, null, tint = N.accent2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Plan ready", style = T.subtitle)
            Text("Build it and keep going until each done criterion is verified, or reply to change it.", style = T.label)
        }
        // The plan card goes when the build actually starts (a refused send kept the text but lost the card).
        KButton("Build this plan", Tone.Accent) { onBuild(criteria) }
    }
}

/** What this chat has cost, and how much of the prompt came from cache. */
@Composable
private fun Spend(loop: AgentLoop) {
    val cost by loop.cost.collectAsStateWithLifecycle()
    val usage by loop.usage.collectAsStateWithLifecycle()
    val known by loop.costKnown.collectAsStateWithLifecycle()
    val prompt = usage.input + usage.cacheRead + usage.cacheWrite
    if (prompt == 0L) return
    val cache = (usage.cacheRead * 100 / prompt).toInt()
    Text((if (known) "$" + "%.3f".format(cost) else "~$" + "%.3f".format(cost) + " (estimated: no price for this model)") +
        " · ${(prompt + usage.output) / 1000}k tokens · cache $cache%",
        style = T.monoSmall, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
}

