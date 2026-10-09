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

    private fun broker(): IWarden? = runCatching {
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
        try {
            coroutineScope {
                val out = async(Dispatchers.IO) { runCatching { ParcelFileDescriptor.AutoCloseInputStream(p.inputStream).use { it.readBytes() } }.getOrDefault(ByteArray(0)) }
                val err = async(Dispatchers.IO) { runCatching { ParcelFileDescriptor.AutoCloseInputStream(p.errorStream).use { it.readBytes() } }.getOrDefault(ByteArray(0)) }
                val done = java.util.concurrent.CompletableFuture<Int>()
                Thread({ done.complete(runCatching { p.waitFor() }.getOrDefault(-1)) }, "warden-wait").apply { isDaemon = true }.start()
                // stdin is written alongside the wait: a child that stops reading must not block us past the timeout
                // (destroy() ends the write). One that exits early closes its stdin: that's not our failure.
                val feed = async(Dispatchers.IO) { runCatching { ParcelFileDescriptor.AutoCloseOutputStream(p.outputStream).use { o -> if (stdin != null) o.write(stdin) } } }
                // Suspending, so Stop cancels it (a blocking get held an install for up to 5 minutes).
                val code = withTimeoutOrNull(timeoutMs) { done.await() }
                if (code == null) runCatching { p.destroy() }
                feed.await()
                Raw(code ?: -1, out.await(), err.await(), System.currentTimeMillis() - t0, timedOut = code == null)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { runCatching { p.destroy() }; throw e
        } catch (e: Exception) { runCatching { p.destroy() }; lastError = "Warden: ${e.message ?: e}"; null }
    }
}
