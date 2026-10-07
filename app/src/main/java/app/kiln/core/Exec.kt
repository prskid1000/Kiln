package app.kiln.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

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
        coroutineScope {
            val out = async(Dispatchers.IO) { p.inputStream.readBytes().decodeToString() }
            val err = async(Dispatchers.IO) { p.errorStream.readBytes().decodeToString() }
            if (stdin != null) runCatching { p.outputStream.use { it.write(stdin) } } else p.outputStream.close()
            val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done) p.destroyForcibly()
            ExecResult(if (done) p.exitValue() else -1, out.await(), err.await(),
                System.currentTimeMillis() - t0, timedOut = !done)
        }
    }
}
