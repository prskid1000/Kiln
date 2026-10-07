package app.kiln.ui

import androidx.lifecycle.viewModelScope
import app.kiln.Graph
import app.kiln.agent.Session
import app.kiln.agent.Turns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Rules, turn review, rewind and fork — kept apart from the core VM.

private val NL = System.lineSeparator()

fun KilnVM.saveRule(name: String, rule: String) = viewModelScope.launch(Dispatchers.IO) {
    val f = state(name).project.memoryFile
    f.parentFile?.mkdirs()
    val text = if (f.isFile) f.readText().trimEnd() else ""
    val header = if ("## Rules" in text) "" else (if (text.isEmpty()) "" else NL + NL) + "## Rules"
    f.writeText(text + header + NL + "- " + rule + NL)
    message.value = "Saved to this app's memory"
}

fun KilnVM.turnChanges(name: String, index: Int): List<Turns.Change> {
    val s = state(name); val session = s.loop.value?.session ?: return emptyList()
    return Turns.changes(session, s.project, index)
}

fun KilnVM.turnDiff(name: String, index: Int, path: String): List<Pair<Char, String>> {
    val s = state(name); val session = s.loop.value?.session ?: return emptyList()
    val before = Turns.before(session, index, path)?.lines() ?: emptyList()
    val after = runCatching { s.project.resolve(path).takeIf { it.isFile }?.readText()?.lines() }.getOrNull() ?: emptyList()
    return lineDiff(before, after)
}

fun KilnVM.revertFile(name: String, index: Int, path: String) {
    val s = state(name); val session = s.loop.value?.session ?: return
    runCatching { Turns.restore(session, s.project, index, listOf(path)) }.onFailure { message.value = it.message }
    IconCache.version.value++
}

/** Put the code back to how it was before message [index] and continue in a new chat from there. */
fun KilnVM.rewind(name: String, index: Int) = branch(name, index, restore = true)

/** Continue in a new chat from before message [index], leaving the code as it is. */
fun KilnVM.fork(name: String, index: Int) = branch(name, index, restore = false)

private fun KilnVM.branch(name: String, index: Int, restore: Boolean) = viewModelScope.launch(Dispatchers.IO) {
    val s = state(name)
    val loop = s.loop.value ?: return@launch
    if (loop.running.value) { message.value = "Stop the current run first"; return@launch }
    runCatching {
        if (restore) Turns.restore(loop.session, s.project, index)
        val forked: Session = Turns.fork(loop.session, Graph.paths.sessions, index)
        s.loop.value = Graph.kiln.openSession(s.project, forked.meta.id)
        s.sessions.value = Session.list(Graph.paths.sessions, name)
    }.onSuccess {
        IconCache.version.value++
        message.value = if (restore) "Rewound: code and chat are back to before that message" else "Forked into a new chat"
    }.onFailure { message.value = "Couldn't ${if (restore) "rewind" else "fork"}: ${it.message}" }
}
