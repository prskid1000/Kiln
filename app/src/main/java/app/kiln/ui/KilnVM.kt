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

        fun slug(label: String): String = label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            .let { if (it.firstOrNull()?.isLetter() == true) it else "app_$it" }.take(36).trimEnd('_').ifBlank { "app" }
    }

    init { refresh(); autoImportPack() }

    private fun listProjects(): List<ProjectInfo> = Graph.paths.projects.listFiles()
        ?.filter { File(it, "kiln.json").isFile }
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

    /** A pack zip dropped in the app's external files dir installs itself. */
    fun autoImportPack() = viewModelScope.launch(Dispatchers.IO) {
        // Not while one is installing (onResume fires again during a long import).
        if (Graph.toolchain.state.value.let { it is Toolchain.State.Ready || it is Toolchain.State.Installing }) return@launch
        val zip = Graph.toolchain.inboxPack() ?: return@launch
        Graph.toolchain.install(zip.inputStream(), zip.length()).onSuccess { zip.delete(); message.value = "Toolchain $it installed" }
            .onFailure { message.value = "Toolchain: ${it.message}" }
    }

    fun importPack(input: InputStream, size: Long) = viewModelScope.launch(Dispatchers.IO) {
        Graph.toolchain.install(input, size).onSuccess { message.value = "Toolchain $it installed" }
            .onFailure { message.value = "Import failed: ${it.message}" }
    }

    fun state(name: String): ProjectState = synchronized(states) {
        states.getOrPut(name) {
            ProjectState(Project(File(Graph.paths.projects, name))).also { s ->
                viewModelScope.launch(Dispatchers.IO) {
                    s.sessions.value = Session.list(Graph.paths.sessions, name)
                    s.loop.value = (s.sessions.value.firstOrNull { it.title.isNotBlank() } ?: s.sessions.value.firstOrNull())?.let { runCatching { Graph.kiln.openSession(s.project, it.id) }.getOrNull() }
                }
            }
        }
    }

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

    fun send(name: String, text: String, attachments: List<app.kiln.agent.Attachment> = emptyList()) {
        if (text.isBlank() && attachments.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            if (Graph.toolchain.state.value !is Toolchain.State.Ready) { message.value = "Install the toolchain first (Settings)"; return@launch }
            val s = state(name)
            warden.value = Graph.warden.status()
            val l = s.loop.value ?: runCatching { Graph.kiln.newSession(s.project) }.getOrElse { message.value = it.message; return@launch }
                .also { s.loop.value = it }
            // One run per project: a second send while it works would be dropped by the loop
            // and (before) stopped the foreground service under the live run.
            if (l.running.value) { message.value = "Kiln is still working on this app — wait, or tap Stop"; return@launch }
            val ctx = getApplication<Application>()
            active.incrementAndGet()
            ContextCompat.startForegroundService(ctx, Intent(ctx, RunService::class.java))
            s.job = runScope.launch {
                try { l.send(text, attachments) } finally {
                    s.sessions.value = Session.list(Graph.paths.sessions, name)
                    if (active.decrementAndGet() == 0) ctx.stopService(Intent(ctx, RunService::class.java))
                    refresh()
                }
            }
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
