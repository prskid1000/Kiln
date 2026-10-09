package app.kiln.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.kiln.Graph
import app.kiln.R
import app.kiln.build.Project
import app.kiln.ui.MainActivity
import org.json.JSONObject
import java.io.File

/**
 * Crashes from real use. The kit's crash hook in a development build of a Kiln app tells Kiln
 * (release builds never do), and Kiln offers to fix it: "Fix with Kiln" opens the project with
 * the crash already in the message box.
 */
class CrashReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra("pkg") ?: return
        val report = intent.getStringExtra("report")?.take(20_000) ?: return
        // Only Kiln's own projects, and only from the app itself when Android can tell us the sender.
        if (!pkg.startsWith("kiln.app.")) return
        // Android 14+: the kit shares its identity, so a report must come from the app it names (any app could
        // otherwise post fake crash notifications and pre-fill the chat).
        if (android.os.Build.VERSION.SDK_INT >= 34 && sentFromPackage != pkg) return
        Graph.init(context.applicationContext as android.app.Application)
        val name = pkg.removePrefix("kiln.app.")
        val dir = File(Graph.paths.projects, name).takeIf { File(it, "kiln.json").isFile } ?: return
        val project = Project(dir)
        // The agent is testing this app right now: it sees the crash itself.
        if (Attention.loops(name)?.running?.value == true) return

        val o = runCatching { JSONObject(report) }.getOrNull() ?: return
        val type = o.optString("rootType").ifBlank { o.optString("type") }.substringAfterLast('.')
        val message = o.optString("rootMessage").ifBlank { o.optString("message") }
        val frames = o.optJSONArray("frames")?.let { a -> (0 until minOf(a.length(), 12)).map { a.getString(it) } }.orEmpty()
        val nl = System.lineSeparator()
        runCatching { File(project.kilnDir, "crashes.log").appendText("${System.currentTimeMillis()} $report$nl") }

        val label = runCatching { project.meta().label }.getOrDefault(name)
        val draft = "The app crashed while I was using it:$nl$type: $message$nl" +
            frames.joinToString(nl) { "    at $it" } + nl + nl + "Find the cause and fix it, then check on the device that it no longer crashes."
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                // Distinct per project: by hash code, another project's crash could rewrite this one's draft.
                .setData(android.net.Uri.parse("kiln://crash/" + android.net.Uri.encode(name)))
                // A best-of copy's crash opens its parent (a fix in the copy would be discarded with it).
                .putExtra(MainActivity.EXTRA_PROJECT, Attempts.ownerOf(project) ?: name).putExtra(MainActivity.EXTRA_DRAFT, draft),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "App crashes", NotificationManager.IMPORTANCE_DEFAULT))
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("$label crashed").setContentText("$type: $message".take(140))
            .setStyle(NotificationCompat.BigTextStyle().bigText("$type: $message".take(600)))
            .setContentIntent(open).setAutoCancel(true).addAction(0, "Fix with Kiln", open).build()
        runCatching { nm.notify("crash:$name", 1, n) }   // tagged per project: hashed ids could collide
    }

    companion object { const val CHANNEL = "kiln_crashes" }
}
