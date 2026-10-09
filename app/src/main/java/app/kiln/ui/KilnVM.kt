package app.kiln.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.kiln.Graph
import app.kiln.agent.AgentLoop
import app.kiln.agent.RunService
import app.kiln.agent.Session
import app.kiln.agent.SessionMeta
import app.kiln.build.Project
import app.kiln.device.Warden
import app.kiln.toolchain.Toolchain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream

/** One row on the Projects screen. */
data class ProjectInfo(val project: Project, val label: String, val pkg: String, val edited: Long)

/**
 * Everything one project's workspace shows: its chat loop, its chat history, and
 * the Run button's progress. Projects are independent — one can be building
 * while you read another's files.
 */
class ProjectState(val project: Project) {
    val loop = MutableStateFlow<AgentLoop?>(null)
    val sessions = MutableStateFlow<List<SessionMeta>>(emptyList())
    /** Non-null while Run is in progress: "Building…", "Installing…", "Launching…". */
    val runStep = MutableStateFlow<String?>(null)
    /** True until the project's last chat has been opened (so the chat doesn't flash its empty state). */
    val loading = MutableStateFlow(true)
    /** Stop was tapped and the run is winding down. */
    val stopping = MutableStateFlow(false)
    internal var job: Job? = null
    /** The file open in the Files tab with its unsaved edits (outlives tab switches and rotation). */
    internal val editor = androidx.compose.runtime.mutableStateOf<EditorBuffer?>(null)
    /** Files picked in the composer, not sent yet. */
    val attachments = androidx.compose.runtime.mutableStateListOf<app.kiln.agent.Attachment>()
    /** Claimed between "start a run" and the run's end: two quick sends can't both start one. */
    internal val starting = java.util.concurrent.atomic.AtomicBoolean(false)
    // Rule proposals the user saved or dismissed (activity ids).
    /** Rule proposals already saved or dismissed, by their text (ids restart in every chat), kept in .kiln/. */
    val decidedRules = MutableStateFlow<Set<String>>(emptySet())
    private val ruleWrites = kotlinx.coroutines.sync.Mutex()
    internal fun decideRule(rule: String) {
        decidedRules.value = decidedRules.value + rule
        // One writer at a time, always writing the current set: two quick decisions saved out of order lost one.
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            ruleWrites.withLock { runCatching { File(project.kilnDir, "decided-rules.txt").writeText(decidedRules.value.joinToString("\n")) } }
        }
    }
    // Text to put in the composer (an element picked in the preview).
    val draft = MutableStateFlow<String?>(null)
    // Read often during composition (every build-progress update): parse kiln.json only when it changed.
    @Volatile private var metaCache: Pair<Long, app.kiln.build.ProjectMeta?> = -1L to null
    private fun meta(): app.kiln.build.ProjectMeta? {
        val stamp = project.metaFile.lastModified()
        if (metaCache.first != stamp) metaCache = stamp to runCatching { project.meta() }.getOrNull()
        return metaCache.second
    }
    val pkg: String get() = meta()?.`package` ?: Project.packageFor(project.name)
    val label: String get() = meta()?.label ?: project.name
}

class KilnVM(app: Application) : AndroidViewModel(app) {
    val toolchain: StateFlow<Toolchain.State> = Graph.toolchain.state
    // Filled by init's refresh(), off the main thread (listing walked every project at launch).
    val projects = MutableStateFlow<List<ProjectInfo>>(emptyList())
    val installed = MutableStateFlow<Set<String>>(emptySet())
    val warden = MutableStateFlow(Warden.Status.NOT_RUNNING)
    // Process-wide: background work (best-of, keep, run) outlives this screen model, and its message must reach the next one.
    val message: MutableStateFlow<String?> get() = messages
    /** A project to open (from a notification). */
    val openRequest = MutableStateFlow<String?>(null)
    /**
     * Runs outlive the screen: Back on the home screen (which finishes the activity on Android 11)
     * or a second activity from the notification must not cancel or duplicate a run. Project state
     * and run jobs are process-wide; the ViewModel is just a view onto them.
     */
    companion object Runs {
        private val states = HashMap<String, ProjectState>()
        /** Forget a project's state (a deleted project, a discarded attempt): a later one of that name starts fresh. */
        internal fun forgetState(name: String): ProjectState? = synchronized(states) { states.remove(name) }
        /** A project's state if it exists (polling must not create one). */
        internal fun existingState(name: String): ProjectState? = synchronized(states) { states[name] }
        /** Projects being deleted: hidden from the list while their files go. */
        internal val deleting: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        internal val runScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        /** Agent runs in flight across all projects; the foreground service lives while > 0. */
        private val active = java.util.concurrent.atomic.AtomicInteger()
        /** No run (or best-of round) is going. */
        internal fun idle(): Boolean = active.get() == 0
        private val serviceLock = Any()
        private val messages = MutableStateFlow<String?>(null)

        /** Keep the run service up across several runs (a best-of round); false if it couldn't start. */
        internal fun holdService(ctx: android.content.Context): Boolean = synchronized(serviceLock) {
            active.incrementAndGet()
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, RunService::class.java)) }
                .onFailure { active.decrementAndGet() }.isSuccess
        }

        internal fun releaseService(ctx: android.content.Context) {
            val last = synchronized(serviceLock) {
                (active.decrementAndGet() == 0).also { if (it) ctx.stopService(Intent(ctx, RunService::class.java)) }
            }
            // The last run is over (a held best-of round's runs never see 0 themselves): the user's crash dialogs come back.
            if (last) kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { runCatching { Graph.testDevice.restoreCrashDialogs() } }
        }

        init { app.kiln.agent.Attention.loops = { name -> synchronized(states) { states[name] }?.loop?.value } }

        fun projectState(name: String): ProjectState = synchronized(states) {
            states.getOrPut(name) {
                ProjectState(Project(File(Graph.paths.projects, name))).also { s ->
                    runScope.launch {
                        s.sessions.value = Session.list(Graph.paths.sessions, name)
                    // Added to (not replacing) any decision made before this load finished.
                    val saved = runCatching { File(s.project.kilnDir, "decided-rules.txt").readLines().filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())
                    s.decidedRules.update { it + saved }
                        val last = (s.sessions.value.firstOrNull { it.title.isNotBlank() } ?: s.sessions.value.firstOrNull())
                            ?.let { runCatching { Graph.kiln.openSession(s.project, it.id) }.getOrNull() }
                        // A run may have started a new chat meanwhile: never replace it with the old one.
                        if (s.loop.value == null) s.loop.value = last
                        s.loading.value = false
                    }
                }
            }
        }

        /**
         * Start an agent run on a project — from the chat, or from a schedule with no screen at all.
         * Returns why it couldn't start, or null. [freshChat] starts a new chat for it.
         */
        suspend fun startRun(ctx0: android.content.Context, name: String, text: String,
                             attachments: List<app.kiln.agent.Attachment> = emptyList(),
                             mode: app.kiln.agent.AgentLoop.Mode = app.kiln.agent.AgentLoop.Mode.BUILD, goal: String? = null,
                             freshChat: Boolean = false, onFinish: () -> Unit = {}): String? {
            if (Graph.toolchain.state.value !is Toolchain.State.Ready) return "The build tools are still setting up — try again in a moment"
            // The run outlives any screen: hold the application, never an Activity.
            val ctx = ctx0.applicationContext
            val s = projectState(name)
            // One run per project, claimed atomically: two quick sends both passed a running check (the loop sets
            // `running` only once it starts), and Stop then cancelled the loser's finished job.
            if (!s.starting.compareAndSet(false, true)) return "Kiln is still working on this app — wait, or tap Stop"
            val l = s.loop.value?.takeIf { !freshChat } ?: runCatching { Graph.kiln.newSession(s.project) }
                .getOrElse { s.starting.set(false); return it.message }.also { s.loop.value = it }
            // The count and the service change together: a run ending as another starts stopped the new one's service.
            // Starting can fail (Android 12+ refuses it from the background): release the claim and say so.
            synchronized(serviceLock) {
                active.incrementAndGet()
                runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, RunService::class.java)) }.onFailure {
                    active.decrementAndGet(); s.starting.set(false)
                    return "Couldn't start the run in the background (${it.message}) — open Kiln and try again"
                }
            }
            // ATOMIC: the body (and its finally, which releases the claim) runs even if cancelled before it starts.
            s.job = runScope.launch(start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
                // Kiln in the background: approvals and questions become actionable notifications.
                // A leftover notification (the last run's "is ready") would make this run's prompts post silently over it.
                app.kiln.agent.Attention.clear(ctx, name)
                val watch = launch {
                    // Also on screen changes: a prompt raised while its chat was showing notifies once the user leaves
                    // it (it waited, unseen, with the run held).
                    val screen = app.kiln.agent.Attention.screen
                    launch { var had = false; kotlinx.coroutines.flow.combine(l.approval, screen) { a, _ -> a }.collect { a ->
                        if (a != null) { had = true; app.kiln.agent.Attention.approval(ctx, name, s.label, a.tool, a.input) }
                        else if (had) { had = false; app.kiln.agent.Attention.clear(ctx, name) } } }   // cleared once, when answered
                    launch { var had = false; kotlinx.coroutines.flow.combine(l.question, screen) { q, _ -> q }.collect { q ->
                        if (q != null) { had = true; app.kiln.agent.Attention.question(ctx, name, s.label, q.text, answerable = q.kind == "text") }
                        else if (had) { had = false; app.kiln.agent.Attention.clear(ctx, name) } } }
                }
                try { l.send(text, attachments, mode, goal) } finally {
                    // Released first: a follow-up sent as the run ends is a new run, not "still working".
                    s.starting.set(false)
                    watch.cancel()
                    // A prompt notification still up (the watcher was stopped before seeing it answered) goes with the run.
                    app.kiln.agent.Attention.clear(ctx, name)
                    val last = l.feed.value.lastOrNull { it.kind == app.kiln.agent.Activity.Kind.ASSISTANT }?.text ?: ""
                    app.kiln.agent.Attention.done(ctx, name, s.label, last.trim().ifBlank { "Run finished" })
                    s.sessions.value = Session.list(Graph.paths.sessions, name)
                    s.stopping.value = false
                    if (synchronized(serviceLock) { (active.decrementAndGet() == 0).also { if (it) ctx.stopService(Intent(ctx, RunService::class.java)) } }) {
                        // The last run is over: the user's crash dialogs come back.
                        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { runCatching { Graph.testDevice.restoreCrashDialogs() } }
                    }
                    onFinish()
                }
            }
            return null
        }

        fun slug(label: String): String = label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            .let { if (it.firstOrNull()?.isLetter() == true) it else "app_$it" }.take(36).trimEnd('_').ifBlank { "app" }
    }

    init { refresh() }

    private fun listProjects(): List<ProjectInfo> = Graph.paths.projects.listFiles()
        ?.filter { File(it, "kiln.json").isFile && !app.kiln.agent.Attempts.isAttempt(it) && it.name !in deleting }
        ?.map { d ->
            val p = Project(d)
            val meta = runCatching { p.meta() }.getOrNull()
            // "Edited" = newest source change, not the dir's mtime (builds touch that).
            val edited = p.files().maxOfOrNull { it.lastModified() } ?: d.lastModified()
            ProjectInfo(p, meta?.label ?: p.name, meta?.`package` ?: Project.packageFor(p.name), edited)
        }?.sortedByDescending { it.edited } ?: emptyList()

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        IconCache.version.value++
        projects.value = listProjects()
        warden.value = Graph.warden.status()
        if (warden.value == Warden.Status.READY) installed.value = runCatching { Graph.device.installedApps().toSet() }.getOrDefault(emptySet())
    }

    /** Set up or update the bundled toolchain (normally already done at app start; a retry after a failure). */
    fun setupToolchain() = viewModelScope.launch(Dispatchers.IO) {
        Graph.toolchain.syncBundled(Graph.app.assets).onFailure { message.value = "Toolchain: ${it.message}" }
    }

    fun state(name: String): ProjectState = projectState(name)

    /** New project; returns its name through [then] so the UI can navigate to it. */
    fun createProject(label: String, prompt: String?, then: (String) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            // A name is free only if no folder has it (the list may not be loaded yet, and hides attempts) and
            // it isn't being deleted.
            val taken = deleting.toSet()
            val base = slug(label)
            val name = generateSequence(1) { it + 1 }.map { if (it == 1) base else "${base}_$it" }.first { it !in taken && !File(Graph.paths.projects, it).exists() }
            Project.create(Graph.paths.projects, name, label.trim(), File(Graph.toolchain.templates(), "compose"))
                .also { File(it.kilnDir, app.kiln.tools.Looks.PENDING).writeText("") }
            projects.value = listProjects()
            launch(Dispatchers.Main) { then(name) }
            if (!prompt.isNullOrBlank()) send(name, prompt)
        }.onFailure { message.value = it.message }
    }

    // In the runs' scope, and `deleting` is always cleared: a finished activity cancelled it half-way and the
    // project stayed hidden until restart.
    fun deleteProject(name: String) = runScope.launch { try { deleteNow(name) } finally { deleting -= name; refresh() } }

    private suspend fun deleteNow(name: String) {
        // Gone from the list at once; the uninstall and file removal below can take a few seconds.
        deleting += name
        projects.value = projects.value.filter { it.project.name != name }
        // A best-of loop would start its next attempt in the folders being deleted.
        cancelBestOf(name)
        val s = synchronized(states) { states.remove(name) }
        // Let a cancelled run actually stop (a tool may be mid-write) before its files go.
        s?.job?.let { j -> j.cancel(); kotlinx.coroutines.withTimeoutOrNull(10_000) { j.join() } }
        // The package the project really uses (kiln.json; the agent may have changed it), not one derived from the name.
        val pkg = runCatching { Project(File(Graph.paths.projects, name)).meta().`package` }.getOrNull() ?: Project.packageFor(name)
        // Always: the cached installed list can be stale (the agent installed it during this run).
        runCatching { Graph.device.uninstall(pkg) }
        // Its secrets go with it.
        runCatching { val p = Project(File(Graph.paths.projects, name)); app.kiln.build.AppSecrets.names(p).forEach { Graph.secrets.put(app.kiln.build.AppSecrets.storeId(p, it), null) } }
        app.kiln.agent.Attempts.list(Graph.paths.projects, name).forEach { t ->
            // Its attempts' runs stop first (they wrote into the deleted folders), and their state goes with them.
            synchronized(states) { states.remove(t.name) }?.job?.let { j -> j.cancel(); kotlinx.coroutines.withTimeoutOrNull(10_000) { j.join() } }
            runCatching { Graph.device.uninstall(t.meta().`package`) }; t.dir.deleteRecursively()
            Session.list(Graph.paths.sessions, t.name).forEach { File(Graph.paths.sessions, it.id).deleteRecursively() }
        }
        forgetAttempts(name)
        File(Graph.paths.projects, name).deleteRecursively()
        Session.list(Graph.paths.sessions, name).forEach { File(Graph.paths.sessions, it.id).deleteRecursively() }
        // Opened again while it was going (the list showed it): drop that state too, then let the name be reused.
        forgetState(name)
    }

    fun newChat(name: String) = viewModelScope.launch(Dispatchers.IO) {
        val s = state(name)
        if (s.loop.value?.running?.value == true) { message.value = "Wait for the current run to finish"; return@launch }
        s.loop.value = runCatching { Graph.kiln.newSession(s.project) }.getOrElse { message.value = it.message; null }
        s.sessions.value = Session.list(Graph.paths.sessions, name)
    }

    fun openSession(name: String, id: String) = viewModelScope.launch(Dispatchers.IO) {
        val s = state(name)
        if (s.loop.value?.running?.value == true) { message.value = "Wait for the current run to finish"; return@launch }
        s.loop.value = Graph.kiln.openSession(s.project, id)
    }

    fun send(name: String, text: String, attachments: List<app.kiln.agent.Attachment> = emptyList(),
             mode: app.kiln.agent.AgentLoop.Mode = app.kiln.agent.AgentLoop.Mode.BUILD, goal: String? = null) {
        if (text.isBlank() && attachments.isEmpty()) return
        // While it works, a message steers the run (delivered at its next step) instead of being refused.
        // Also while a run is starting: the message is delivered when it begins, not refused.
        val s0 = state(name)
        // A run that's starting may be on a new chat whose loop isn't here yet: don't steer the old one.
        if (s0.starting.get() && s0.loop.value?.running?.value != true) {
            message.value = "Kiln is starting a run — send that again in a moment"
            viewModelScope.launch { restoreDraft(name, text, attachments) }; return
        }
        s0.loop.value?.takeIf { it.running.value }?.let { l ->
            if (attachments.isNotEmpty()) message.value = "Files can be attached once this run finishes"
            l.steer(text); return
        }
        viewModelScope.launch(Dispatchers.IO) {
            warden.value = Graph.warden.status()
            startRun(getApplication(), name, text, attachments, mode, goal, onFinish = { refresh() })?.let {
                message.value = it
                // Not started: the message goes back into the composer instead of being lost.
                restoreDraft(name, text, attachments)
            }
        }
    }

    /** Put an unsent message (and its files) back into the project's composer. */
    internal suspend fun restoreDraft(name: String, text: String, attachments: List<app.kiln.agent.Attachment>) =
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            val s = state(name)
            if (text.isNotBlank()) s.draft.value = text + (s.draft.value ?: "")
            s.attachments.addAll(attachments)
        }

    // Cancelling waits for the model's stream to close, which can take a few seconds: say so at once.
    fun stop(name: String) { state(name).let { it.stopping.value = true; it.job?.cancel() } }

    /** Build → install → launch, the same path the agent's run_app takes. */
    fun run(name: String) = runScope.launch {   // a build keeps going if the screen goes away
        val s = state(name)
        if (s.runStep.value != null) return@launch
        try {
            s.runStep.value = "Building…"
            val b = Graph.builds.build(s.project) { s.runStep.value = "Building · $it" }
            if (!b.ok) {
                val first = b.diagnostics.firstOrNull { it.severity == "error" }
                message.value = "Build failed" + (first?.let { ": ${it.message.take(120)}" } ?: "")
                return@launch
            }
            if (Graph.warden.status() != Warden.Status.READY) { message.value = "Built. Start Warden to install it."; return@launch }
            s.runStep.value = "Installing…"
            val i = Graph.device.install(File(b.apk!!))
            if (!i.ok) { message.value = "Install failed: ${i.all.trim().take(160)}"; return@launch }
            s.runStep.value = "Launching…"
            Graph.device.launch(s.pkg)
            refresh()
        } finally { s.runStep.value = null; IconCache.version.value++ }
    }

    fun uninstall(name: String) = viewModelScope.launch(Dispatchers.IO) {
        val s = state(name)
        s.runStep.value = "Uninstalling…"   // the top bar's spinner
        val r = try { Graph.device.uninstall(s.pkg) } finally { s.runStep.value = null }
        message.value = if (r.ok) "Uninstalled" else r.all.trim()
        refresh()
    }

    fun clearData(name: String) = viewModelScope.launch(Dispatchers.IO) {
        val s = state(name)
        s.runStep.value = "Clearing app data…"
        val r = try { Graph.device.clearData(s.pkg) } finally { s.runStep.value = null }
        message.value = if (r.ok) "App data cleared" else r.all.trim()
    }

}
