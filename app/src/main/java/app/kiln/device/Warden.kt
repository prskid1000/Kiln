package app.kiln.device

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.kiln.core.ExecResult
import app.warden.api.IWarden
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    /** Run [argv] as shell. [stdin] is streamed to the process; output is collected. */
    suspend fun exec(argv: List<String>, stdin: ByteArray? = null, timeoutMs: Long = 120_000): ExecResult =
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val svc = broker() ?: return@withContext ExecResult(-1, "", "Warden is not running", 0)
            val p = try { svc.newProcess(argv.toTypedArray(), emptyArray(), "/") } catch (e: SecurityException) {
                return@withContext ExecResult(-1, "", "Warden denied Kiln — grant it in the Warden app", 0)
            }
            coroutineScope {
                val out = async(Dispatchers.IO) { ParcelFileDescriptor.AutoCloseInputStream(p.inputStream).use { it.readBytes() } }
                val err = async(Dispatchers.IO) { ParcelFileDescriptor.AutoCloseInputStream(p.errorStream).use { it.readBytes() } }
                ParcelFileDescriptor.AutoCloseOutputStream(p.outputStream).use { o -> if (stdin != null) o.write(stdin) }
                val code = withTimeoutOrNull(timeoutMs) { async(Dispatchers.IO) { p.waitFor() }.await() }
                if (code == null) runCatching { p.destroy() }
                ExecResult(code ?: -1, out.await().decodeToString(), err.await().decodeToString(),
                    System.currentTimeMillis() - t0, timedOut = code == null)
            }
        }

    /** Like [exec] but returns raw stdout bytes (screencap). */
    suspend fun execBytes(argv: List<String>, timeoutMs: Long = 60_000): Pair<Int, ByteArray> =
        withContext(Dispatchers.IO) {
            val svc = broker() ?: return@withContext -1 to ByteArray(0)
            val p = svc.newProcess(argv.toTypedArray(), emptyArray(), "/")
            coroutineScope {
                val out = async(Dispatchers.IO) { ParcelFileDescriptor.AutoCloseInputStream(p.inputStream).use { it.readBytes() } }
                async(Dispatchers.IO) { ParcelFileDescriptor.AutoCloseInputStream(p.errorStream).use { it.readBytes() } }
                ParcelFileDescriptor.AutoCloseOutputStream(p.outputStream).close()
                val code = withTimeoutOrNull(timeoutMs) { async(Dispatchers.IO) { p.waitFor() }.await() } ?: -1
                code to out.await()
            }
        }
}
