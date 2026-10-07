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
 * The on-device toolchain (see toolchain/build_pack.py): JDK, kotlinc,
 * R8, apksigner, aapt2, the app kit. Bundled in the APK as components and set up
 * on launch ([syncBundled]): each verified file by file, moved into place whole,
 * and switched in atomically. A half-installed component is never used.
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
        val ok = dir != null && File(dir, "VERSION.json").isFile
        lastReady = if (ok) dir else null
        _state.value = if (ok) State.Ready(dir!!.name, dir) else State.Missing
    }

    /** The installed toolchain stays usable while a new pack imports, and after one fails to. */
    @Volatile private var lastReady: File? = null
    val dir: File? get() = (state.value as? State.Ready)?.dir ?: lastReady

    /** Called before the active toolchain is swapped (the warm compiler JVM must not outlive its files). */
    var beforeSwitch: (suspend () -> Unit)? = null
    private val installLock = kotlinx.coroutines.sync.Mutex()
    fun require(): File = dir ?: error("The toolchain isn't set up yet — reopen Kiln to finish setting it up.")

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

    /**
     * Install or update the toolchain from the components bundled in the APK (the .kpk files in assets/toolchain,
     * see toolchain/build_pack.py). A component is unpacked only when its content hash isn't
     * installed yet — so an APK update installs exactly what changed, and an ordinary launch reads
     * seven small headers and does nothing. Each component lands in c/<name>/<hash>/ through a
     * staging dir and a rename; the active toolchain is a set of links to them, switched atomically.
     */
    suspend fun syncBundled(assets: android.content.res.AssetManager): Result<String> = withContext(Dispatchers.IO) {
        if (!installLock.tryLock()) { installLock.lock(); installLock.unlock(); return@withContext Result.success(state.value.let { (it as? State.Ready)?.version ?: "" }) }
        try { syncLocked(assets) } finally { installLock.unlock() }
    }

    private class Bundled(val asset: String, val name: String, val hash: String, val abi: String, val size: Long, val files: Map<String, String>)

    private fun readHeader(assets: android.content.res.AssetManager, asset: String): Bundled =
        ZipInputStream(assets.open(asset).buffered(1 shl 16)).use { z ->
            val e = z.nextEntry
            require(e != null && e.name == "component.json") { "$asset has no component.json" }
            val o = parseJson(z.readBytes().decodeToString()) as JsonObject
            Bundled(asset, o.str("name")!!, o.str("payloadSha256")!!, o.str("abi") ?: "",
                (o["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                (o["files"] as JsonObject).mapValues { (it.value as JsonPrimitive).content })
        }

    private suspend fun syncLocked(assets: android.content.res.AssetManager): Result<String> = runCatching {
        val bundled = (assets.list("toolchain") ?: emptyArray()).filter { it.endsWith(".kpk") }.sorted()
            .map { readHeader(assets, "toolchain/$it") }
        if (bundled.isEmpty()) {
            // A build without components (toolchain not built): keep whatever is installed.
            refresh(); return@runCatching (state.value as? State.Ready)?.version ?: error("this build of Kiln has no toolchain bundled")
        }
        val abi = android.os.Build.SUPPORTED_ABIS.first()
        bundled.firstOrNull { it.abi != abi }?.let { error("this Kiln build is for ${it.abi}; this device needs $abi — install the $abi build") }

        val store = File(paths.toolchainRoot, "c")
        fun dirOf(b: Bundled) = File(store, "${b.name}/${b.hash}")
        // Installed means complete: the header and every file. Anything damaged is simply installed again.
        fun complete(b: Bundled) = File(dirOf(b), "component.json").isFile && b.files.keys.all { File(dirOf(b), it).isFile }
        val missing = bundled.filter { !complete(it) }
        val total = missing.sumOf { it.size }.coerceAtLeast(1)
        var done = 0L
        for (b in missing) {
            val staging = File(store, ".staging-${b.name}").apply { deleteTree(this); mkdirs() }
            try {
                ZipInputStream(assets.open(b.asset).buffered(1 shl 16)).use { z ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val e = z.nextEntry ?: break
                        if (e.isDirectory || e.name == "component.json") continue
                        val want = b.files[e.name] ?: error("${b.name}: unexpected file ${e.name}")
                        val out = File(staging, e.name).canonicalFile
                        require(out.path.startsWith(staging.canonicalPath + File.separator)) { "bad entry ${e.name}" }
                        out.parentFile?.mkdirs()
                        val md = MessageDigest.getInstance("SHA-256")
                        out.outputStream().use { o ->
                            while (true) { val n = z.read(buf); if (n < 0) break; o.write(buf, 0, n); md.update(buf, 0, n); done += n }
                        }
                        require(hex(md.digest()) == want) { "${b.name}: checksum mismatch in ${e.name}" }
                        _state.value = State.Installing(done, total, "Setting up ${b.name}")
                    }
                }
                val got = staging.walkTopDown().filter { it.isFile }.count()
                require(got == b.files.size) { "${b.name}: ${b.files.size - got} files missing" }
                // The header goes in last: a component dir with component.json is complete.
                File(staging, "component.json").writeText("""{"name":"${b.name}","payloadSha256":"${b.hash}"}""")
                val target = dirOf(b).apply { parentFile?.mkdirs(); deleteTree(this) }
                check(staging.renameTo(target)) { "could not move ${b.name} into place" }
            } finally { deleteTree(staging) }
        }

        // The active toolchain: one dir of links to the components, named by the set's hash.
        val setId = hex(MessageDigest.getInstance("SHA-256").digest(bundled.joinToString("|") { "${it.name}=${it.hash}" }.toByteArray())).take(16)
        val set = File(paths.toolchainRoot, "sets/$setId")
        if (!File(set, "VERSION.json").isFile) {
            val tmp = File(paths.toolchainRoot, "sets/.tmp-$setId").apply { deleteTree(this); mkdirs() }
            for (b in bundled) for (top in dirOf(b).listFiles().orEmpty().filter { it.isDirectory })
                android.system.Os.symlink(top.path, File(tmp, top.name).path)
            File(tmp, "VERSION.json").writeText("{\"version\":\"$setId\",\"abi\":\"$abi\",\"components\":{" +
                bundled.joinToString(",") { "\"${it.name}\":\"${it.hash}\"" } + "}}")
            deleteTree(set)
            check(tmp.renameTo(set)) { "could not activate the toolchain" }
        }
        if ((state.value as? State.Ready)?.dir?.canonicalPath != set.canonicalPath) {
            beforeSwitch?.invoke()
            File(paths.toolchainRoot, "current.tmp").apply { writeText("sets/$setId") }.renameTo(File(paths.toolchainRoot, "current"))
        }
        refresh()
        prune(setId, bundled.map { dirOf(it).canonicalPath }.toSet())
        setId
    }.onFailure {
        refresh()   // the installed toolchain, if there is one, stays in use
        if (_state.value !is State.Ready) _state.value = State.Failed(it.message ?: it.toString())
    }

    /** Remove component versions and link sets the active toolchain doesn't use, and pre-component packs. */
    private fun prune(activeSet: String, keep: Set<String>) {
        val root = paths.toolchainRoot
        File(root, "sets").listFiles()?.filter { it.name != activeSet }?.forEach { deleteTree(it) }
        File(root, "c").listFiles()?.forEach { comp ->
            comp.listFiles()?.filter { it.canonicalPath !in keep }?.forEach { deleteTree(it) }
        }
        root.listFiles()?.filter { it.name !in setOf("c", "sets", "current") }?.forEach { deleteTree(it) }
    }

    /**
     * Delete without following links. A set is links to shared components, and File.deleteRecursively
     * follows them — pruning an old set that way empties the components the new set still uses.
     */
    private fun deleteTree(f: File) {
        val path = f.toPath()
        if (java.nio.file.Files.isSymbolicLink(path)) { java.nio.file.Files.deleteIfExists(path); return }
        if (f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
        f.delete()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
