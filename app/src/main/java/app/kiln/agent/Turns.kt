package app.kiln.agent

import app.kiln.build.Project
import java.io.File

/**
 * A snapshot of the project's source before every user message, so a turn can be reviewed
 * (what did it change?) and rewound (code *and* chat back to that point). Snapshots live with
 * the session, keyed by the transcript index of the user message that started the turn.
 * Projects are small (sources only; build output and .kiln are excluded), so a full copy is
 * simpler and safer than diffs.
 */
object Turns {
    private fun root(session: Session) = File(session.dir, "turns")
    private fun dir(session: Session, index: Int) = File(root(session), index.toString())

    fun snapshot(session: Session, project: Project, index: Int) {
        val d = dir(session, index)
        if (d.exists()) return
        for (f in project.files()) { val out = File(d, project.rel(f)); out.parentFile?.mkdirs(); f.copyTo(out, overwrite = true) }
        d.mkdirs()   // an empty project still gets a (empty) snapshot
    }

    /** Indexes of user messages that have a snapshot, oldest first. */
    fun indexes(session: Session): List<Int> =
        root(session).listFiles()?.mapNotNull { it.name.toIntOrNull() }?.sorted() ?: emptyList()

    data class Change(val path: String, val kind: Kind) { enum class Kind { ADDED, MODIFIED, DELETED } }

    /** What turn [index] changed: its snapshot vs the next turn's snapshot, or vs now for the latest turn. */
    fun changes(session: Session, project: Project, index: Int): List<Change> {
        val before = dir(session, index).takeIf { it.isDirectory } ?: return emptyList()
        val nextIdx = indexes(session).firstOrNull { it > index }
        val after: Map<String, File> = if (nextIdx != null) files(dir(session, nextIdx))
            else project.files().associateBy { project.rel(it) }
        val old = files(before)
        val out = mutableListOf<Change>()
        for ((p, f) in after) {
            val o = old[p]
            if (o == null) out += Change(p, Change.Kind.ADDED)
            else if (!o.readBytes().contentEquals(f.readBytes())) out += Change(p, Change.Kind.MODIFIED)
        }
        for (p in old.keys - after.keys) out += Change(p, Change.Kind.DELETED)
        return out.sortedBy { it.path }
    }

    /** File content as it was before turn [index] (null if it didn't exist yet). */
    fun before(session: Session, index: Int, path: String): String? =
        File(dir(session, index), path).takeIf { it.isFile }?.readText()

    /** Put [paths] (or the whole project) back the way they were before turn [index]. */
    fun restore(session: Session, project: Project, index: Int, paths: List<String>? = null) {
        val snap = dir(session, index)
        require(snap.isDirectory) { "no snapshot for that message" }
        val saved = files(snap)
        val targets = paths ?: (saved.keys + project.files().map { project.rel(it) }).distinct()
        for (p in targets) {
            val dest = project.resolve(p)
            val src = saved[p]
            if (src != null) { dest.parentFile?.mkdirs(); src.copyTo(dest, overwrite = true) } else dest.delete()
        }
    }

    /**
     * A new session holding this one's transcript up to (not including) message [index], with the
     * snapshots before it. The original stays untouched: the transcript is append-only.
     */
    fun fork(session: Session, sessionsRoot: File, index: Int): Session {
        val s = Session.create(sessionsRoot, session.meta.project, session.meta.systemPrompt)
        session.messages.take(index).forEach { s.append(it) }
        s.updateMeta { it.copy(title = "${session.meta.title.ifBlank { "Chat" }.take(50)} (from here)") }
        for (i in indexes(session).filter { it < index }) dir(session, i).copyRecursively(File(s.dir, "turns/$i"), overwrite = true)
        return s
    }

    private fun files(d: File): Map<String, File> =
        d.walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(d).invariantSeparatorsPath }
}