package app.kiln.toolchain

import android.util.Log
import app.kiln.core.Exec
import app.kiln.core.ExecResult
import app.kiln.core.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One warm JVM per app process running app.kiln.toolserver.ToolServer.
 *
 * kotlinc cold-starts in ~4 s on the phone and ~1 s warm; javac, d8 and
 * apksigner share the same process. Requests are serialized (the tools are not
 * re-entrant in one JVM). The process is restarted if it dies, and stopped
 * after [idleMs] without use to give the memory back.
 */
class ToolHost(private val paths: Paths, private val toolchain: Toolchain) {
    private val lock = Mutex()
    private val ids = AtomicLong()
    private var proc: Process? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStream? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var idleJob: Job? = null
    private val idleMs = 10 * 60_000L

    /** Heap for the tool JVM. Kotlin + Compose compiles want headroom. */
    var heapMb = 1536

    val running: Boolean get() = proc?.isAlive == true

    private fun start() {
        val d = toolchain.require()
        val kc = toolchain.kotlinc(d)
        val cp = listOf(
            toolchain.tool("toolserver.jar", d), toolchain.tool("r8.jar", d), toolchain.tool("apksigner.jar", d),
            File(kc, "kotlin-compiler.jar"), File(kc, "kotlin-stdlib.jar"), File(kc, "kotlin-reflect.jar"),
            File(kc, "kotlin-script-runtime.jar"), File(kc, "kotlinx-coroutines-core-jvm.jar"),
            File(kc, "annotations-13.0.jar"),
        ).filter { it.exists() }.joinToString(":")
        val argv = listOf(
            Exec.LINKER, toolchain.launcher(d).absolutePath, toolchain.jdk(d).absolutePath,
            "-Xmx${heapMb}m", "-XX:+UseSerialGC", "-Xss8m",
            "-Djava.io.tmpdir=${paths.tmp.absolutePath}", "-Djava.awt.headless=true",
            "-Djava.security.manager=allow", "-Dkotlin.colors.enabled=false",
            "-Didea.io.use.nio2=true", "-Didea.home.path=${paths.tmp.absolutePath}",
            "-cp", cp, "app.kiln.toolserver.ToolServer",
        )
        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        pb.environment().putAll(toolchain.env(d))
        val p = pb.start()
        val r = p.inputStream.bufferedReader()
        // Skip JVM warnings until the server says READY.
        while (true) {
            val line = r.readLine() ?: error("tool server exited during start")
            if (line.startsWith("READY")) break
            Log.i("Kiln", "toolserver: $line")
        }
        proc = p; reader = r; writer = p.outputStream
    }

    fun stop() {
        proc?.let { p -> runCatching { writer?.close() }; if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly() }
        proc = null; reader = null; writer = null
    }

    /** Run [tool] (kotlinc | javac | d8 | apksigner | ping) with absolute-path [args]. */
    suspend fun run(tool: String, args: List<String>): ExecResult = lock.withLock {
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            if (proc?.isAlive != true) { stop(); start() }
            val id = ids.incrementAndGet().toString()
            val payload = (listOf(id, tool) + args).joinToString("\u0000")
            val w = writer!!
            w.write((Base64.getEncoder().encodeToString(payload.toByteArray()) + "\n").toByteArray())
            w.flush()
            var result: ExecResult? = null
            while (result == null) {
                val line = reader!!.readLine()
                if (line == null) { stop(); result = ExecResult(-1, "", "tool server died", System.currentTimeMillis() - t0); break }
                if (!line.startsWith("RES $id ")) { Log.i("Kiln", "toolserver: $line"); continue }
                val parts = line.split(" ", limit = 4)
                val out = if (parts.size > 3) String(Base64.getDecoder().decode(parts[3])) else ""
                result = ExecResult(parts[2].toInt(), out, "", System.currentTimeMillis() - t0)
            }
            scheduleIdleStop()
            result!!
        }
    }

    private fun scheduleIdleStop() {
        idleJob?.cancel()
        idleJob = scope.launch { delay(idleMs); lock.withLock { stop() } }
    }
}
