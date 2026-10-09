package app.kiln.kit

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
/** The kit's shared HTTP client (connection pool, timeouts). Use [KHttp]; this is for raw OkHttp calls. */
object KNet {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
}

/** Notifications with one default channel. Ask for the permission with [rememberPermission]. */
object KNotify {
    private const val CHANNEL = "kiln_default"

    fun post(context: Context, title: String, text: String, id: Int = title.hashCode()) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "General", NotificationManager.IMPORTANCE_DEFAULT))
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pi = open?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi).setAutoCancel(true).build()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            nm.notify(id, n)
    }
}

/** A runtime permission: `granted`, and `request()` to ask. */
class KPermission internal constructor(granted: Boolean, private val ask: () -> Unit) {
    var granted by mutableStateOf(granted)
        internal set
    fun request() = ask()
}

/** Remember a runtime permission, e.g. `rememberPermission(Manifest.permission.CAMERA)`. */
@Composable
fun rememberPermission(permission: String): KPermission {
    val context = LocalContext.current
    val initial = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    var holder: KPermission? = null
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        holder?.granted = ok
    }
    val perm = remember(permission) { KPermission(initial) { launcher.launch(permission) } }
    holder = perm
    // Granted elsewhere (app settings, another screen): re-checked whenever the app comes back.
    KOnResume { perm.granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED }
    return perm
}
