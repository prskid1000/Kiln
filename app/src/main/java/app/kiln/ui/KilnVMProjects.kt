package app.kiln.ui

import androidx.lifecycle.viewModelScope
import app.kiln.Graph
import app.kiln.agent.Session
import app.kiln.build.Project
import app.kiln.core.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.File

// Remix, transcript export and chat search.

/** Copy a project under a new name (installs side by side with the original). */
fun KilnVM.duplicateProject(name: String, then: (String) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
    runCatching {
        val from = state(name)
        val label = from.label + " copy"
        val taken = projects.value.map { it.project.name }.toSet()
        val base = KilnVM.slug(label).take(36)
        val newName = generateSequence(1) { it + 1 }.map { if (it == 1) base else "${base}_$it" }.first { it !in taken }
        Project.duplicate(Graph.paths.projects, from.project, newName, label)
        refresh().join()
        launch(Dispatchers.Main) { then(newName) }
        message.value = "Copied to \"$label\""
    }.onFailure { message.value = "Couldn't copy: ${it.message}" }
}

/** The open chat as Markdown: what was asked, what was said, and which tools ran. */
fun KilnVM.transcriptMarkdown(name: String): String? {
    val s = state(name)
    val session = s.loop.value?.session ?: return null
    val nl = System.lineSeparator()
    return buildString {
        append("# ").append(session.meta.title.ifBlank { s.label }).append(nl).append(nl)
        // A copy taken under the session's lock: the run may be appending (iterating the live list crashed).
        for (m in synchronized(session) { session.messages.toList() }) for (b in m.content) {
            val o = b as? JsonObject ?: continue
            when (o.str("type")) {
                "text" -> {
                    val t = o.str("text").orEmpty()
                    if (t.isBlank() || t.startsWith("<system-reminder>")) continue
                    if (m.role == "user") append("**You:** ") else append("")
                    append(t.trim()).append(nl).append(nl)
                }
                "tool_use" -> append("> ").append(o.str("name")).append(nl).append(nl)
            }
        }
    }
}

/** Chats in this project whose text contains [q] (case-insensitive), newest first. */
fun KilnVM.searchChats(name: String, q: String): Set<String> {
    if (q.isBlank()) return emptySet()
    return Session.list(Graph.paths.sessions, name).filter { meta ->
        meta.title.contains(q, ignoreCase = true) ||
            runCatching { File(File(Graph.paths.sessions, meta.id), "transcript.jsonl").useLines { lines -> lines.any { it.contains(q, ignoreCase = true) } } }
                .getOrDefault(false)
    }.map { it.id }.toSet()
}
