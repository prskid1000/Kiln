package app.kiln.kit

import android.content.Context
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
 */
object KilnCrash {
    const val TAG = "KILN-CRASH"
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val pkg = context.packageName
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
