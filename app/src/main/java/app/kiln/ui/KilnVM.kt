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

/** UI state for the single page: current project, its session loop, toolchain and Warden status. */
class KilnVM(app: Application) : AndroidViewModel(app) {
    val toolchain: StateFlow<Toolchain.State> = Graph.toolchain.state
    val projects = MutableStateFlow(listProjects())
    val project = MutableStateFlow<Project?>(null)
    val loop = MutableStateFlow<AgentLoop?>(null)
    val sessions = MutableStateFlow<List<SessionMeta>>(emptyList())
    val warden = MutableStateFlow(Warden.Status.NOT_RUNNING)
    val message = MutableStateFlow<String?>(null)
    private var runJob: Job? = null

    init {
        refreshWarden()
        autoImportPack()
        projects.value.firstOrNull()?.let { open(it) }
    }

    private fun listProjects(): List<Project> = Graph.paths.projects.listFiles()
        ?.filter { File(it, "kiln.json").isFile }?.sortedByDescending { it.lastModified() }?.map { Project(it) } ?: emptyList()

    fun refreshWarden() = viewModelScope.launch(Dispatchers.IO) { warden.value = Graph.warden.status() }

    /** A pack zip dropped in the app's external files dir installs itself. */
    fun autoImportPack() = viewModelScope.launch(Dispatchers.IO) {
        if (Graph.toolchain.state.value is Toolchain.State.Ready) return@launch
        val zip = Graph.toolchain.inboxPack() ?: return@launch
        Graph.toolchain.install(zip.inputStream(), zip.length()).onSuccess { zip.delete(); message.value = "Toolchain $it installed" }
    }

    fun importPack(input: InputStream, size: Long) = viewModelScope.launch(Dispatchers.IO) {
        Graph.toolchain.install(input, size).onSuccess { message.value = "Toolchain $it installed" }
            .onFailure { message.value = "Import failed: ${it.message}" }
    }

    fun createProject(name: String, label: String) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val p = Project.create(Graph.paths.projects, name, label, File(Graph.toolchain.templates(), "compose"))
            projects.value = listProjects()
            open(p)
        }.onFailure { message.value = it.message }
    }

    fun open(p: Project) = viewModelScope.launch(Dispatchers.IO) {
        if (loop.value?.running?.value == true) { message.value = "Stop the current run first"; return@launch }
        project.value = p
        sessions.value = Session.list(Graph.paths.sessions, p.name)
        loop.value = sessions.value.firstOrNull()?.let { Graph.kiln.openSession(p, it.id) }
    }

    fun newChat() = viewModelScope.launch(Dispatchers.IO) {
        val p = project.value ?: return@launch
        if (loop.value?.running?.value == true) return@launch
        refreshWarden().join()
        loop.value = runCatching { Graph.kiln.newSession(p) }.getOrElse { message.value = it.message; null }
        sessions.value = Session.list(Graph.paths.sessions, p.name)
    }

    fun openSession(id: String) = viewModelScope.launch(Dispatchers.IO) {
        val p = project.value ?: return@launch
        loop.value = Graph.kiln.openSession(p, id)
    }

    fun send(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            if (Graph.toolchain.state.value !is Toolchain.State.Ready) { message.value = "Install the toolchain first (Settings)"; return@launch }
            val l = loop.value ?: run { newChat().join(); loop.value } ?: return@launch
            val ctx = getApplication<Application>()
            ContextCompat.startForegroundService(ctx, Intent(ctx, RunService::class.java))
            runJob = viewModelScope.launch(Dispatchers.IO) {
                try { l.send(text) } finally { ctx.stopService(Intent(ctx, RunService::class.java)) }
            }
        }
    }

    fun stop() { runJob?.cancel() }
}
