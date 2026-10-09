package app.kiln.agent

import app.kiln.core.KJ
import app.kiln.llm.Msg
import app.kiln.llm.Usage
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class SessionMeta(
    val id: String,
    val project: String,
    val created: Long,
    val title: String = "",
    /** Frozen for the whole session: same bytes every request, so the cache prefix holds. */
    val systemPrompt: String = "",
    val usage: Usage = Usage(),
    val costUsd: Double = 0.0,
    /** Why the last run stopped early ("Stopped after 80 steps…"), shown again when the chat reopens. */
    val stopNotice: String = "",
    /** That stop was an error (a failed model call, a crash in the loop), not a limit or the user. */
    val stopIsError: Boolean = false,
)

/**
 * One conversation with the agent. The transcript is append-only JSONL — a
 * message, once written, is never edited or removed (prompt caching and
 * thinking-block validity both depend on it). Context is trimmed server-side
 * (context editing / compaction), never by rewriting history here.
 */
class Session private constructor(val dir: File, meta: SessionMeta, messages: List<Msg>) {
    private val log = File(dir, "transcript.jsonl")
    private val metaFile = File(dir, "meta.json")
    private val _messages = messages.toMutableList()
    val messages: List<Msg> get() = _messages
    var meta: SessionMeta = meta
        private set

    @Synchronized fun append(m: Msg) {
        _messages += m
        // Synced, so a crash right after can't leave the turn half-written.
        java.io.FileOutputStream(log, true).use { o -> o.write((KJ.encodeToString(Msg.serializer(), m) + "\n").toByteArray()); o.fd.sync() }
    }

    @Synchronized fun updateMeta(f: (SessionMeta) -> SessionMeta) {
        meta = f(meta)
        metaFile.writeText(KJ.encodeToString(SessionMeta.serializer(), meta))
    }

    val spillDir: File get() = File(dir, "spill").apply { mkdirs() }

    companion object {
        fun create(root: File, project: String, systemPrompt: String): Session {
            // Unique even when two chats start in the same millisecond (two schedules at 02:00 shared a folder).
            // mkdir() claims the folder atomically: only the call that creates it gets it.
            root.mkdirs()
            var id: String; var n = 0; var claimed: Boolean
            // Bounded: a full disk (mkdir always false) must fail, not spin forever.
            do { id = "s" + System.currentTimeMillis().toString(36) + (if (n++ == 0) "" else "-$n"); claimed = File(root, id).mkdir() } while (!claimed && n < 100)
            if (!claimed) throw java.io.IOException("can't create a chat folder in $root (is the storage full?)")
            val dir = File(root, id)
            val s = Session(dir, SessionMeta(id, project, System.currentTimeMillis(), systemPrompt = systemPrompt), emptyList())
            s.updateMeta { it }
            return s
        }

        fun open(dir: File): Session? = runCatching {
            val meta = KJ.decodeFromString(SessionMeta.serializer(), File(dir, "meta.json").readText())
            // A torn last line (killed mid-write) is cut off: the next append would otherwise land on it and be lost too.
            File(dir, "transcript.jsonl").takeIf { it.isFile && it.length() > 0 }?.let { t ->
                java.io.RandomAccessFile(t, "rw").use { raf ->
                    raf.seek(raf.length() - 1)
                    if (raf.read() != '\n'.code) {
                        var pos = raf.length() - 1
                        while (pos > 0) { raf.seek(pos - 1); if (raf.read() == '\n'.code) break; pos-- }
                        raf.setLength(pos)
                    }
                }
            }
            val msgs = File(dir, "transcript.jsonl").takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }
                // A crash mid-write can leave a partial last line: drop unreadable lines, keep the session.
                ?.mapNotNull { runCatching { KJ.decodeFromString(Msg.serializer(), it) }.getOrNull() } ?: emptyList()
            Session(dir, meta, msgs)
        }.getOrNull()

        fun list(root: File, project: String): List<SessionMeta> = root.listFiles()?.mapNotNull { d ->
            runCatching { KJ.decodeFromString(SessionMeta.serializer(), File(d, "meta.json").readText()) }.getOrNull()
        }?.filter { it.project == project }?.sortedByDescending { it.created } ?: emptyList()
    }
}
