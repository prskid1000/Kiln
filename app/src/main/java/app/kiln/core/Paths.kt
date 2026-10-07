package app.kiln.core

import android.content.Context
import java.io.File

/** Where everything lives inside the app sandbox. */
class Paths(context: Context) {
    val files: File = context.filesDir
    /** Installed toolchain packs: toolchain/<version>/, plus `current` → version name. */
    val toolchainRoot = File(files, "toolchain")
    /** User projects, one directory each. */
    val projects = File(files, "projects")
    /** Agent sessions (append-only JSONL transcripts). */
    val sessions = File(files, "sessions")
    /** Large tool outputs spilled to disk, readable by the agent. */
    val spill = File(files, "spill")
    /** Scratch for the JVM (java.io.tmpdir) and builds. */
    val tmp = File(context.cacheDir, "tmp")
    val evals = File(files, "evals")
    /** Drop-in folder adb (and the user) can write without permissions: a pack zip here is auto-imported. */
    val inbox: File? = context.getExternalFilesDir(null)

    init { listOf(toolchainRoot, projects, sessions, spill, tmp, evals).forEach { it.mkdirs() } }
}
