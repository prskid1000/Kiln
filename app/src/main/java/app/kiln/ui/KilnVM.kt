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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
    internal var job: Job? = null
    // Rule proposals the user saved or dismissed (activity ids).
    val decidedRules = MutableStateFlow<Set<Int>>(emptySet())
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
    val projects = MutableStateFlow(listProjects())
    val installed = MutableStateFlow<Set<String>>(emptySet())
    val warden = MutableStateFlow(Warden.Status.NOT_RUNNING)
    val message = MutableStateFlow<String?>(null)
    /** A project to open (from a notification). */
    val openRequest = MutableStateFlow<String?>(null)
    /**
     * Runs outlive the screen: Back on the home screen (which finishes the activity on Android 11)
     * or a second activity from the notification must not cancel or duplicate a run. Project state
     * and run jobs are process-wide; the ViewModel is just a view onto them.
     */
    companion object Runs {
        private val states = HashMap<String, ProjectState>()
        private val runScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        /** Agent runs in flight across all projects; the foreground service lives while > 0. */
        private val active = java.util.concurrent.atomic.AtomicInteger()

        init { app.kiln.agent.Attention.loops = { name -> synchronized(states) { states[name] }?.loop?.value } }

        fun projectState(name: String): ProjectState = synchronized(states) {
            states.getOrPut(name) {
                ProjectState(Project(File(Graph.paths.projects, name))).also { s ->
                    runScope.launch {
                        s.sessions.value = Session.list(Graph.paths.sessions, name)
                        val last = (s.sessions.value.firstOrNull { it.title.isNotBlank() } ?: s.sessions.value.firstOrNull())
                            ?.let { runCatching { Graph.kiln.openSession(s.project, it.id) }.getOrNull() }
                        // A run may have started a new chat meanwhile: never replace it with the old one.
                        if (s.loop.value == null) s.loop.value = last
                    }
                }
            }
        }

        /**
         * Start an agent run on a project — from the chat, or from a schedule with no screen at all.
         * Returns why it couldn't start, or null. [freshChat] starts a new chat for it.
         */
        suspend fun startRun(ctx: android.content.Context, name: String, text: String,
                             attachments: List<app.kiln.agent.Attachment> = emptyList(),
                             mode: app.kiln.agent.AgentLoop.Mode = app.kiln.agent.AgentLoop.Mode.BUILD, goal: String? = null,
                             freshChat: Boolean = false, onFinish: () -> Unit = {}): String? {
            if (Graph.toolchain.state.value !is Toolchain.State.Ready) return "The build tools are still setting up — try again in a moment"
            val s = projectState(name)
            val l = s.loop.value?.takeIf { !freshChat } ?: runCatching { Graph.kiln.newSession(s.project) }.getOrElse { return it.message }
                .also { s.loop.value = it }
            // One run per project: a second send while it works would be dropped by the loop
            // and (before) stopped the foreground service under the live run.
            if (l.running.value) return "Kiln is still working on this app — wait, or tap Stop"
            active.incrementAndGet()
            ContextCompat.startForegroundService(ctx, Intent(ctx, RunService::class.java))
            s.job = runScope.launch {
                // Kiln in the background: approvals and questions become actionable notifications.
                val watch = launch {
                    launch { l.approval.collect { a -> if (a != null) app.kiln.agent.Attention.approval(ctx, name, s.label, a.tool, a.input) else app.kiln.agent.Attention.clear(ctx, name) } }
                    launch { l.question.collect { q -> if (q != null) app.kiln.agent.Attention.question(ctx, name, s.label, q.text) } }
                }
                try { l.send(text, attachments, mode, goal) } finally {
                    watch.cancel()
                    val last = l.feed.value.lastOrNull { it.kind == app.kiln.agent.Activity.Kind.ASSISTANT }?.text ?: ""
                    app.kiln.agent.Attention.done(ctx, name, s.label, last.trim().ifBlank { "Run finished" })
                    s.sessions.value = Session.list(Graph.paths.sessions, name)
                    if (active.decrementAndGet() == 0) ctx.stopService(Intent(ctx, RunService::class.java))
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
        ?.filter { File(it, "kiln.json").isFile && !app.kiln.agent.Attempts.isAttempt(it) }
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
            val taken = projects.value.map { it.project.name }.toSet()
            val base = slug(label)
            val name = generateSequence(1) { it + 1 }.map { if (it == 1) base else "${base}_$it" }.first { it !in taken }
            Project.create(Graph.paths.projects, name, label.trim(), File(Graph.toolchain.templates(), "compose"))
            projects.value = listProjects()
            launch(Dispatchers.Main) { then(name) }
            if (!prompt.isNullOrBlank()) send(name, prompt)
        }.onFailure { message.value = it.message }
    }

    fun deleteProject(name: String) = viewModelScope.launch(Dispatchers.IO) {
        val s = synchronized(states) { states.remove(name) }
        s?.job?.cancel()
        val pkg = Project.packageFor(name)
        if (pkg in installed.value) Graph.device.uninstall(pkg)
        // Its secrets go with it.
        runCatching { val p = Project(File(Graph.paths.projects, name)); app.kiln.build.AppSecrets.names(p).forEach { Graph.secrets.put(app.kiln.build.AppSecrets.storeId(p, it), null) } }
        app.kiln.agent.Attempts.list(Graph.paths.projects, name).forEach { runCatching { Graph.device.uninstall(it.meta().`package`) }; it.dir.deleteRecursively() }
        File(Graph.paths.projects, name).deleteRecursively()
        Session.list(Graph.paths.sessions, name).forEach { File(Graph.paths.sessions, it.id).deleteRecursively() }
        refresh()
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
        state(name).loop.value?.takeIf { it.running.value }?.let { l ->
            if (attachments.isNotEmpty()) message.value = "Files can be attached once this run finishes"
            l.steer(text); return
        }
        viewModelScope.launch(Dispatchers.IO) {
            warden.value = Graph.warden.status()
            startRun(getApplication(), name, text, attachments, mode, goal, onFinish = { refresh() })?.let { message.value = it }
        }
    }

    fun stop(name: String) { state(name).job?.cancel() }

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
        val r = Graph.device.uninstall(state(name).pkg)
        message.value = if (r.ok) "Uninstalled" else r.all.trim()
        refresh()
    }

    fun clearData(name: String) = viewModelScope.launch(Dispatchers.IO) {
        val r = Graph.device.clearData(state(name).pkg)
        message.value = if (r.ok) "App data cleared" else r.all.trim()
    }

}
