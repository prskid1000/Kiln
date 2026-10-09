package app.kiln.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

data class ExecResult(val code: Int, val out: String, val err: String, val ms: Long, val timedOut: Boolean = false) {
    val ok get() = code == 0 && !timedOut
    /** stdout + stderr, for tools that print diagnostics on either. */
    val all get() = if (err.isBlank()) out else if (out.isBlank()) err else "$out\n$err"
}

/**
 * Runs toolchain binaries from inside the app sandbox.
 *
 * An app at targetSdk 36 may map, but not exec, files in its own data dir
 * (SELinux: execute without execute_no_trans on app_data_file). The system
 * linker can: `linker64 <binary> args…` loads and runs the binary from a
 * context policy permits — the same model Vessel uses for Wine. Binaries must
 * be PIE (static ET_EXEC binaries are refused by the linker).
 */
object Exec {
    const val LINKER = "/system/bin/linker64"

    suspend fun linker(
        binary: File,
        args: List<String>,
        env: Map<String, String> = emptyMap(),
        cwd: File? = null,
        stdin: ByteArray? = null,
        timeoutMs: Long = 600_000,
    ): ExecResult = run(listOf(LINKER, binary.absolutePath) + args, env, cwd, stdin, timeoutMs)

    suspend fun run(
        argv: List<String>,
        env: Map<String, String> = emptyMap(),
        cwd: File? = null,
        stdin: ByteArray? = null,
        timeoutMs: Long = 600_000,
    ): ExecResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val pb = ProcessBuilder(argv)
        if (cwd != null) pb.directory(cwd)
        pb.environment().putAll(env)
        val p = pb.start()
        // Stop and the timeout both end the process; its pipes are closed too, since a background grandchild
        // (`server &`) holding them would keep the readers — and this call — waiting forever.
        // Readers outside this call's scope: a read a grandchild keeps blocked can't hold the call open.
        val readers = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        fun kill() { p.destroyForcibly(); runCatching { p.inputStream.close() }; runCatching { p.errorStream.close() } }
        try {
            coroutineScope {
                val out = readers.async { runCatching { p.inputStream.readBytes() }.getOrDefault(ByteArray(0)).decodeToString() }
                val err = readers.async { runCatching { p.errorStream.readBytes() }.getOrDefault(ByteArray(0)).decodeToString() }
                launch(Dispatchers.IO) { if (stdin != null) runCatching { p.outputStream.use { it.write(stdin) } } else runCatching { p.outputStream.close() } }
                // Polled, so cancellation is seen within 50 ms (a blocking waitFor ignored Stop).
                val deadline = t0 + timeoutMs
                while (p.isAlive && System.currentTimeMillis() < deadline) delay(50)
                val done = !p.isAlive
                if (!done) kill()
                // The child exited, but a grandchild may still hold its output open: don't wait on it for long.
                val o = withTimeoutOrNull(5_000) { out.await() } ?: run { kill(); withTimeoutOrNull(2_000) { out.await() } ?: "" }
                val e = withTimeoutOrNull(5_000) { err.await() } ?: run { kill(); withTimeoutOrNull(2_000) { err.await() } ?: "" }
                ExecResult(if (done) p.exitValue() else -1, o, e, System.currentTimeMillis() - t0, timedOut = !done)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { kill(); throw e }
    }
}
