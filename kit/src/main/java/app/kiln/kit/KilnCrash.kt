package app.kiln.kit

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Uncaught exceptions are logged as one structured line before the process dies:
 *
 *     E KILN-CRASH: {"pkg":…,"thread":…,"type":…,"message":…,"frames":[…]}
 *
 * Kiln's `last_crash` tool reads exactly this line (and falls back to the
 * standard FATAL EXCEPTION block for crashes before the hook was installed).
 * In a development build the same report also goes to Kiln, for a "Fix with Kiln" notification.
 */
object KilnCrash {
    const val TAG = "KILN-CRASH"
    @Volatile private var installed = false

    fun install(context0: Context) {
        // The handler lives for the whole process: hold the application, not the first Activity.
        val context = context0.applicationContext
        if (installed) return
        installed = true
        val pkg = context.packageName
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                var root: Throwable = error
                while (root.cause != null && root.cause !== root) root = root.cause!!
                val frames = JSONArray()
                (error.stackTrace + root.stackTrace).distinct().take(40).forEach { frames.put(it.toString()) }
                val line = JSONObject()
                    .put("pkg", pkg).put("thread", thread.name)
                    .put("type", error.javaClass.name).put("message", error.message ?: "")
                    .put("rootType", root.javaClass.name).put("rootMessage", root.message ?: "")
                    .put("frames", frames)
                Log.e(TAG, line.toString())
                // A development build tells Kiln on this phone, which offers to fix it. Release builds
                // never do: on someone else's phone the report would go to whatever app is there.
                if (debuggable) {
                    val report = Intent("app.kiln.CRASH").setClassName("app.kiln", "app.kiln.agent.CrashReceiver")
                        .putExtra("pkg", pkg).putExtra("report", line.toString())
                    // Android 14+: say who we are, so Kiln can refuse reports other apps forge in our name.
                    if (android.os.Build.VERSION.SDK_INT >= 34) context.sendBroadcast(report, null,
                        android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
                    else context.sendBroadcast(report)
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }
}

/** App logging under one tag, so `logcat` filters stay simple. */
object KLog {
    const val TAG = "KILN-APP"
    fun i(msg: String) { Log.i(TAG, msg) }
    fun w(msg: String, t: Throwable? = null) { Log.w(TAG, msg, t) }
    fun e(msg: String, t: Throwable? = null) { Log.e(TAG, msg, t) }
}
