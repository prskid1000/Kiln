package app.kiln.device

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.kiln.core.ExecResult
import app.warden.api.IWarden
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bridge to the Warden broker (shell identity, uid 2000).
 *
 * Building needs no Warden; everything that touches *another* app does:
 * silent install, launch/stop, logcat of other apps, screenshots, input,
 * uiautomator. Kiln must be granted in the Warden app. The broker enforces the
 * grant and audits every call.
 */
class Warden(private val context: Context) {
    enum class Status { NOT_INSTALLED, NOT_RUNNING, NOT_GRANTED, READY }

    /**
     * The broker provider belongs to the real Warden: package app.warden, signed with Warden's key. Otherwise an app
     * that took the authority (or the package name, with Warden not installed) would get every command, APK and
     * typed text, and could answer anything.
     */
    private fun genuine(): Boolean = runCatching {
        val pm = context.packageManager
        if (pm.resolveContentProvider("app.warden.broker", 0)?.packageName != "app.warden") return false
        val signers = pm.getPackageInfo("app.warden", android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo?.apkContentsSigners ?: return false
        signers.any { s ->
            java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } == WARDEN_CERT_SHA256
        }
    }.getOrDefault(false)

    private fun broker(): IWarden? = runCatching {
        if (!genuine()) return null
        val reply = context.contentResolver.call(Uri.parse("content://app.warden.broker"), "getBinder", null, null)
        val b = reply?.getBinder("binder")?.takeIf { it.isBinderAlive } ?: return null
        IWarden.Stub.asInterface(b)
    }.getOrNull()

    fun status(): Status {
        val installed = runCatching { context.packageManager.getPackageInfo("app.warden", 0); true }.getOrDefault(false)
        if (!installed) return Status.NOT_INSTALLED
        val svc = broker() ?: return Status.NOT_RUNNING
        val granted = runCatching { svc.checkGrant(context.packageName) == 1 }.getOrDefault(false)
        return if (granted) Status.READY else Status.NOT_GRANTED
    }

    val ready: Boolean get() = status() == Status.READY

    /** Run [argv] as shell. [stdin] is streamed to the process; output is collected. Never throws. */
    suspend fun exec(argv: List<String>, stdin: ByteArray? = null, timeoutMs: Long = 120_000): ExecResult {
        val (code, out, err, ms, timedOut) = run(argv, stdin, timeoutMs) ?: return ExecResult(-1, "", lastError, 0)
        return ExecResult(code, out.decodeToString(), err.decodeToString(), ms, timedOut)
    }

    /** Like [exec] but returns raw stdout bytes (screencap). */
    suspend fun execBytes(argv: List<String>, timeoutMs: Long = 60_000): Pair<Int, ByteArray> =
        run(argv, null, timeoutMs)?.let { it.code to it.out } ?: (-1 to ByteArray(0))

    private data class Raw(val code: Int, val out: ByteArray, val err: ByteArray, val ms: Long, val timedOut: Boolean)
    @Volatile private var lastError = ""

    /**
     * The process runs remotely; waitFor() is a blocking binder call that ignores cancellation,
     * so it waits on its own thread, outside the timeout, and the process is destroyed when the
     * timeout fires (which also ends the output reads). Broker failures (died, denied, too many
     * processes, broken stdin) come back as null + [lastError] instead of crashing the caller.
     */
    private suspend fun run(argv: List<String>, stdin: ByteArray?, timeoutMs: Long): Raw? = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val svc = broker() ?: run { lastError = "Warden is not running"; return@withContext null }
        val p = try { svc.newProcess(argv.toTypedArray(), emptyArray(), "/") } catch (e: SecurityException) {
            lastError = "Warden denied Kiln — grant it in the Warden app"; return@withContext null
        } catch (e: Exception) { lastError = "Warden: ${e.message ?: e}"; return@withContext null }
        // Closed on Stop or an error too: otherwise reads a background child keeps blocked leak their threads.
        val streams = java.util.Collections.synchronizedList(mutableListOf<java.io.Closeable>())
        fun closeAll() = streams.forEach { runCatching { it.close() } }
        try {
            coroutineScope {
                // Readers outside this scope: a background child (`logcat &`) keeps the pipes open after sh exits,
                // and blocking reads that never end held the call — and Stop — forever.
                val readers = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
                // Read into buffers we keep: if the grace below runs out, what arrived is still returned, and closing
                // the streams ends the blocked reads (their threads were leaked before).
                val outStream = ParcelFileDescriptor.AutoCloseInputStream(p.inputStream)
                val errStream = ParcelFileDescriptor.AutoCloseInputStream(p.errorStream)
                streams += outStream; streams += errStream
                val outBuf = java.io.ByteArrayOutputStream(); val errBuf = java.io.ByteArrayOutputStream()
                fun pump(i: java.io.InputStream, b: java.io.ByteArrayOutputStream) = runCatching {
                    i.use { val chunk = ByteArray(65_536); while (true) { val n = it.read(chunk); if (n < 0) break; synchronized(b) { b.write(chunk, 0, n) } } }
                }
                val out = readers.async { pump(outStream, outBuf) }
                val err = readers.async { pump(errStream, errBuf) }
                val done = java.util.concurrent.CompletableFuture<Int>()
                Thread({ done.complete(runCatching { p.waitFor() }.getOrDefault(-1)) }, "warden-wait").apply { isDaemon = true }.start()
                // stdin is written alongside the wait: a child that stops reading must not block us past the timeout
                // (destroy() ends the write). One that exits early closes its stdin: that's not our failure.
                val feed = readers.async { runCatching { ParcelFileDescriptor.AutoCloseOutputStream(p.outputStream).use { o -> if (stdin != null) o.write(stdin) } } }
                // Suspending, so Stop cancels it (a blocking get held an install for up to 5 minutes).
                // On cancel, destroy here: the scope can't finish (and the outer catch can't run) until the blocking
                // readers end, and they end only when the child does.
                val code = try { withTimeoutOrNull(timeoutMs) { done.await() } }
                    catch (e: kotlinx.coroutines.CancellationException) { runCatching { p.destroy() }; throw e }
                if (code == null) runCatching { p.destroy() }
                withTimeoutOrNull(5_000) { feed.await() }
                // After exit, a short grace for the rest of the output; a background child holding the pipe can't keep us here.
                // 30 s: a big output (a screenshot) may still be streaming after the command exits.
                if (withTimeoutOrNull(30_000) { out.await() } == null) { runCatching { p.destroy() }; runCatching { outStream.close() } }
                if (withTimeoutOrNull(2_000) { err.await() } == null) runCatching { errStream.close() }
                val o = synchronized(outBuf) { outBuf.toByteArray() }
                val e = synchronized(errBuf) { errBuf.toByteArray() }
                Raw(code ?: -1, o, e, System.currentTimeMillis() - t0, timedOut = code == null)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { runCatching { p.destroy() }; closeAll(); throw e
        } catch (e: Exception) { runCatching { p.destroy() }; closeAll(); lastError = "Warden: ${e.message ?: e}"; null }
    }
}

/** SHA-256 of Warden's signing certificate (debug and release builds share it). */
private const val WARDEN_CERT_SHA256 = "b5b3cd575546a8bfa3829a00aaaeb559e37d20362c815978fbfba1b532f4985a"
