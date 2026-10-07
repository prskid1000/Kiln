package app.kiln.toolchain

import app.kiln.core.Paths
import app.kiln.core.parseJson
import app.kiln.core.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * The on-device toolchain pack (see toolchain/build_pack.py): JDK, kotlinc,
 * R8, apksigner, aapt2, the app kit. Installed by unpacking a pack zip into
 * toolchain/<version>/, verifying every file's sha256, then switching
 * toolchain/current atomically. A half-installed pack is never used.
 */
class Toolchain(private val paths: Paths) {

    sealed interface State {
        data object Missing : State
        data class Installing(val done: Long, val total: Long, val step: String) : State
        data class Ready(val version: String, val dir: File) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state

    init { refresh() }

    fun refresh() {
        val cur = File(paths.toolchainRoot, "current").takeIf { it.isFile }?.readText()?.trim()
        val dir = cur?.let { File(paths.toolchainRoot, it) }
        _state.value = if (dir != null && File(dir, "VERSION.json").isFile) State.Ready(cur, dir) else State.Missing
    }

    val dir: File? get() = (state.value as? State.Ready)?.dir
    fun require(): File = dir ?: error("Toolchain not installed — open Settings → Toolchain and import the pack.")

    // ---- layout ----
    fun jdk(d: File = require()) = File(d, "jdk")
    fun launcher(d: File = require()) = File(d, "bin/kilnjava")
    fun aapt2(d: File = require()) = File(d, "bin/aapt2")
    fun libDir(d: File = require()) = File(d, "lib")
    fun androidJar(d: File = require()) = File(d, "sdk/android.jar")
    fun kotlinc(d: File = require()) = File(d, "kotlinc")
    fun tool(name: String, d: File = require()) = File(d, "tools/$name")
    fun kit(d: File = require()) = File(d, "kit")
    fun kitClasspath(d: File = require()): List<File> =
        File(kit(d), "classpath").listFiles { f -> f.extension == "jar" }?.sortedBy { it.name } ?: emptyList()
    fun kitDex(d: File = require()): List<File> =
        File(kit(d), "dex").listFiles { f -> f.extension == "dex" }?.sortedBy { it.name } ?: emptyList()
    fun kitResZips(d: File = require()): List<File> =
        File(kit(d), "res").listFiles { f -> f.extension == "zip" }?.sortedBy { it.name } ?: emptyList()
    fun kitApi(d: File = require()): String = File(kit(d), "API.md").takeIf { it.isFile }?.readText() ?: ""
    fun templates(d: File = require()) = File(d, "templates")

    /** Environment every toolchain process gets (shared libs for aapt2 and the JVM). */
    fun env(d: File = require()): Map<String, String> = mapOf(
        "LD_LIBRARY_PATH" to listOf(libDir(d), File(jdk(d), "lib"), File(jdk(d), "lib/server")).joinToString(":"),
        "TMPDIR" to paths.tmp.absolutePath,
        "HOME" to paths.files.absolutePath,
    )

    /** A pack zip dropped into the app's external files dir (adb push / file manager). */
    fun inboxPack(): File? = paths.inbox?.listFiles { f -> f.name.startsWith("kiln-toolchain") && f.extension == "zip" }
        ?.maxByOrNull { it.lastModified() }

    suspend fun install(input: InputStream, totalBytes: Long): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val staging = File(paths.toolchainRoot, ".staging").apply { deleteRecursively(); mkdirs() }
            var done = 0L
            ZipInputStream(input.buffered(1 shl 16)).use { zin ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val e = zin.nextEntry ?: break
                    val out = File(staging, e.name).canonicalFile
                    require(out.path.startsWith(staging.canonicalPath)) { "bad entry ${e.name}" }
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o ->
                        while (true) {
                            val n = zin.read(buf); if (n < 0) break
                            o.write(buf, 0, n); done += n
                        }
                    }
                    _state.value = State.Installing(done, totalBytes, e.name)
                }
            }
            val meta = parseJson(File(staging, "VERSION.json").readText()) as JsonObject
            val version = meta.str("version") ?: error("pack has no version")
            val files = meta["files"] as JsonObject
            var checked = 0
            for ((rel, sha) in files) {
                val f = File(staging, rel)
                require(f.isFile) { "pack is missing $rel" }
                require(sha256(f) == (sha as JsonPrimitive).content) { "checksum mismatch: $rel" }
                checked++
                _state.value = State.Installing(checked.toLong(), files.size.toLong(), "verify $rel")
            }
            val target = File(paths.toolchainRoot, version)
            target.deleteRecursively()
            check(staging.renameTo(target)) { "could not move pack into place" }
            File(paths.toolchainRoot, "current.tmp").apply { writeText(version) }
                .renameTo(File(paths.toolchainRoot, "current"))
            paths.toolchainRoot.listFiles()?.filter { it.isDirectory && it.name != version && !it.name.startsWith(".") }
                ?.forEach { it.deleteRecursively() }
            refresh()
            version
        }.onFailure { _state.value = State.Failed(it.message ?: it.toString()) }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
