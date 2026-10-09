package app.kiln.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import app.kiln.Graph
import app.kiln.agent.Attempts
import app.kiln.ui.theme.N
import app.kiln.ui.theme.T
import app.kiln.ui.theme.vCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import app.kiln.agent.Session
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Best of N: one request, several independent attempts in hidden copies, keep the best.

private val attemptLists = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<List<String>>>()

/** The names of a project's attempt copies (empty when there are none). */
fun KilnVM.attempts(name: String): MutableStateFlow<List<String>> = attemptLists.getOrPut(name) {
    MutableStateFlow(Attempts.list(Graph.paths.projects, name).map { it.name })
}

// In the runs' scope: Back on Android 11 finishes the activity and clears the ViewModel, which stopped attempts 2..n.
fun KilnVM.bestOf(name: String, request: String, n: Int) = KilnVM.runScope.launch {
    val s = state(name)
    if (s.loop.value?.running?.value == true) { message.value = "Wait for the current run to finish"; restoreDraft(name, request, emptyList()); return@launch }
    discardAttempts(name).join()
    // Deleted or stopped while that discard ran: don't start a round on it.
    if (!isActive || name in KilnVM.deleting) return@launch
    // Registered after that discard (which cancels the previous round's loop), so Discard and Delete can stop this one.
    bestOfJobs[name] = coroutineContext[kotlinx.coroutines.Job]!!
    val tries = runCatching { Attempts.create(Graph.paths.projects, s.project, n, request) }
        .getOrElse { message.value = "Couldn't start the attempts: ${it.message}"; restoreDraft(name, request, emptyList()); return@launch }
    attempts(name).value = tries.map { it.name }
    // One after another: they share the hidden test screen. The run service is held for the whole round: between
    // attempts the count fell to 0 and the service stopped, and Android 12+ won't start it again from the background.
    val app = getApplication<android.app.Application>()
    if (!KilnVM.holdService(app)) { message.value = "Couldn't start the attempts in the background — open Kiln and try again"; restoreDraft(name, request, emptyList()); return@launch }
    try { for ((i, t) in tries.withIndex()) {
        val prompt = request + "\n\n(This is attempt ${i + 1} of $n: separate copies of the app are each trying this " +
            "on their own and the user will keep the best one. Make your own best version, and verify it on the device.)"
        KilnVM.startRun(getApplication(), t.name, prompt, freshChat = true)?.let {
            message.value = it; if (i == 0) restoreDraft(name, request, emptyList()); return@launch }
        state(t.name).job?.join()
    } } finally { KilnVM.releaseService(app) }
    message.value = "$n attempts are ready — compare them and keep one"
}

// Keep and discard run in the runs' scope: leaving the app mid-way left folders, apps and states behind.
fun KilnVM.keepAttempt(name: String, attempt: String) = KilnVM.runScope.launch {
    val s = state(name)
    // Not while the project itself is working: adopting replaces its sources under the running agent.
    if (s.loop.value?.running?.value == true || s.starting.get()) { message.value = "Wait for the current run to finish"; return@launch }
    val chosen = Attempts.list(Graph.paths.projects, name).firstOrNull { it.name == attempt } ?: return@launch
    // Its label is read before discarding: the discard deletes the chosen copy too.
    val label = runCatching { chosen.meta().label.substringAfterLast("· ") }.getOrDefault(attempt)
    runCatching { Attempts.adopt(s.project, chosen) }
        .onSuccess { cp -> discardAttempts(name).join(); IconCache.version.value++
            message.value = "Kept $label. Run it to install; checkpoint \"$cp\" has the old version." }
        .onFailure { message.value = "Couldn't keep it: ${it.message}" }
}

fun KilnVM.discardAttempts(name: String) = KilnVM.runScope.launch {
    // The round's loop first: it would start the next attempt on a deleted folder.
    cancelBestOf(name)
    // Off the screen first: the bar and sheet looked their states up every second and brought them back mid-discard.
    attempts(name).value = emptyList()
    for (t in Attempts.list(Graph.paths.projects, name)) {
        // Wait for its run to stop: it would keep writing into the folder being deleted. Its state goes too
        // (the next round reuses the names, and showed this round's screenshot as "Done").
        KilnVM.forgetState(t.name)?.job?.let { j -> j.cancel(); kotlinx.coroutines.withTimeoutOrNull(10_000) { j.join() } }
        // Its chats too: the next round reuses the name and would reopen this round's session.
        Session.list(Graph.paths.sessions, t.name).forEach { java.io.File(Graph.paths.sessions, it.id).deleteRecursively() }
        runCatching { Graph.device.uninstall(t.meta().`package`) }
    }
    Attempts.discard(Graph.paths.projects, name)
    attempts(name).value = emptyList()
}

private val bestOfJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

/** Stop a project's best-of loop (not the caller's own) and wait for it. */
internal suspend fun cancelBestOf(name: String) {
    val me = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
    bestOfJobs.remove(name)?.takeIf { it !== me }?.let { j -> j.cancel(); kotlinx.coroutines.withTimeoutOrNull(10_000) { j.join() } }
}

/** Forget a deleted project's attempts: a new project with the same name must not show them. */
internal fun forgetAttempts(name: String) { attemptLists.remove(name) }

/** "3 attempts" above the composer while there are attempts to compare. */
@Composable
fun AttemptsBar(vm: KilnVM, ps: ProjectState) {
    val names by vm.attempts(ps.project.name).collectAsState()
    if (names.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    // Runs start one after another (each attempt's loop appears when its turn comes): re-check each second.
    val working by androidx.compose.runtime.produceState(0, names) {
        while (true) { value = names.count { KilnVM.existingState(it)?.loop?.value?.running?.value == true }; kotlinx.coroutines.delay(1000) }
    }
    // The attempt that's running now: its approvals and questions show here (no other screen shows its loop, and
    // a prompt nobody sees stalled the whole round).
    val active by androidx.compose.runtime.produceState<app.kiln.agent.AgentLoop?>(null, names) {
        while (true) { value = names.firstNotNullOfOrNull { KilnVM.existingState(it)?.loop?.value?.takeIf { l -> l.running.value } }; kotlinx.coroutines.delay(1000) }
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().vCard(N.shapeLg, N.accent.copy(alpha = 0.5f))
        .clickable { open = true }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.CallSplit, null, tint = N.accent2)
        Spacer(Modifier.width(10.dp))
        Text("${names.size} attempts" + if (working > 0) " · working" else " · ready to compare", style = T.subtitle, modifier = Modifier.weight(1f))
        Text("Compare", style = T.label.copy(color = N.accent2))
    }
    active?.let { Prompts(it) }
    if (open) AttemptsSheet(vm, ps, names) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttemptsSheet(vm: KilnVM, ps: ProjectState, names: List<String>, onDismiss: () -> Unit) {
    var preview by remember { mutableStateOf<String?>(null) }
    preview?.let { n -> PreviewSheet(vm, vm.state(n), onDismiss = { preview = null }) { preview = null } }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = N.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Compare attempts", style = T.cardTitle)
            Text("Each copy did the same request on its own. Try them in Preview, then keep one — your current version " +
                "is saved as a checkpoint first.", style = T.bodySmall)
            val anyRunning = names.any { KilnVM.existingState(it)?.loop?.value?.running?.value == true }
            names.forEachIndexed { i, n ->
                val st = vm.state(n)
                val loop by st.loop.collectAsState()
                val running = loop?.running?.collectAsState()?.value == true
                val feed = loop?.feed?.collectAsState()?.value.orEmpty()
                val shot = feed.lastOrNull { it.images.isNotEmpty() }?.images?.firstOrNull()
                val summary = feed.lastOrNull { it.kind == app.kiln.agent.Activity.Kind.ASSISTANT }?.text?.trim()?.take(240)
                Column(Modifier.fillMaxWidth().vCard(N.shapeLg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Try ${i + 1}", style = T.subtitle)
                    Text(when { running -> "Working…"; loop == null || feed.isEmpty() -> "Waiting its turn"; else -> "Done" }, style = T.label)
                    shot?.let { png ->
                        val bmp = rememberDecoded(png, 900)   // decoded off the main thread, downsampled
                        bmp?.let { Image(it, "Try ${i + 1}", Modifier.heightIn(max = 300.dp).clip(N.shapeMd)) }
                    }
                    summary?.let { Markdown(it, Modifier.fillMaxWidth()) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KButton("Preview") { preview = n }
                        KButton("Keep this", Tone.Accent) { if (!anyRunning) { vm.keepAttempt(ps.project.name, n); onDismiss() } }
                    }
                }
            }
            KButton("Discard all") { vm.discardAttempts(ps.project.name); onDismiss() }
        }
    }
}
