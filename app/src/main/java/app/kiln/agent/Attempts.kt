package app.kiln.agent

import app.kiln.build.Project
import app.kiln.build.ProjectMeta
import app.kiln.core.KJPretty
import java.io.File

/**
 * Best of N: the same request carried out independently in N hidden copies of a project, so the
 * user can compare the results and keep one. A copy is a full duplicate with its own package
 * (builds and installs don't collide) and a marker naming the project it belongs to; adopting one
 * copies its files back under the original package and discards the rest.
 */
object Attempts {
    private const val MARKER = "attempt-of"

    fun isAttempt(dir: File) = File(dir, ".kiln/$MARKER").isFile

    fun ownerOf(p: Project): String? = runCatching { File(p.kilnDir, MARKER).readText().trim() }.getOrNull()

    /** This project's attempts, in order. */
    fun list(root: File, owner: String): List<Project> =
        (root.listFiles() ?: emptyArray()).filter { isAttempt(it) && runCatching { File(it, ".kiln/$MARKER").readText().trim() }.getOrNull() == owner }
            .sortedBy { it.name }.map { Project(it) }

    fun create(root: File, from: Project, n: Int, request: String): List<Project> {
        discard(root, from.name)
        val label = from.meta().label
        return (1..n).map { i ->
            val name = "${from.name.take(30)}_try$i"
            File(root, name).deleteRecursively()
            Project.duplicate(root, from, name, "$label · try $i").also {
                File(it.kilnDir, MARKER).writeText(from.name)
                File(it.kilnDir, "attempt-request.txt").writeText(request)
            }
        }
    }

    /** Make [original] what [attempt] built (files only; its own name, label and version stay). */
    fun adopt(original: Project, attempt: Project): String {
        val meta = original.meta()
        // Restorable: the restore tool (or asking Kiln to undo) brings the previous version back.
        val checkpoint = app.kiln.tools.CheckpointTool.snapshot(original, "before keeping ${attempt.name.substringAfterLast('_')}")
        // Files the attempt doesn't have are gone in it too.
        val keep = attempt.files().map { attempt.rel(it).replace(attempt.meta().`package`.replace('.', '/'), meta.`package`.replace('.', '/')) }.toSet()
        original.files().filter { original.rel(it) !in keep }.forEach { it.delete() }
        Project.copySources(attempt, original.dir, meta.`package`)
        original.metaFile.writeText(KJPretty.encodeToString(ProjectMeta.serializer(),
            original.meta().copy(label = meta.label, versionCode = meta.versionCode, versionName = meta.versionName)))
        return checkpoint
    }

    fun discard(root: File, owner: String) = list(root, owner).forEach { it.dir.deleteRecursively() }
}
