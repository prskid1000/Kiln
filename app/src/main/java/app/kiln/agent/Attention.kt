package app.kiln.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import app.kiln.R
import app.kiln.ui.MainActivity

/**
 * Notifications for when a run needs the user and Kiln isn't on screen: an approval (Allow /
 * Deny right from the notification), a question (reply inline), and "done". Answers go back to
 * the waiting loop through [AttentionReceiver].
 */
object Attention {
    const val CHANNEL = "kiln_attention"
    private const val KEY_REPLY = "reply"

    /** Set by the UI: the loop for a project name, so a notification action can answer it. */
    var loops: (String) -> AgentLoop? = { null }
    /** Set by MainActivity: when Kiln is on screen, the in-app cards are enough. */
    @Volatile var visible = false

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).also {
        if (it.getNotificationChannel(CHANNEL) == null)
            it.createNotificationChannel(NotificationChannel(CHANNEL, "Needs you", NotificationManager.IMPORTANCE_HIGH))
    }
    private fun id(project: String) = 1000 + (project.hashCode() and 0xfff)

    private fun open(ctx: Context) = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE)

    // The request code is per project: Android matches PendingIntents ignoring extras, so one shared code made
    // project A's Allow carry project B (FLAG_UPDATE_CURRENT replaced it) and approve the wrong request.
    private fun action(ctx: Context, project: String, what: String, req: Int, mutable: Boolean = false) =
        PendingIntent.getBroadcast(ctx, id(project) * 4 + req, Intent(ctx, AttentionReceiver::class.java).setAction(what).putExtra("project", project),
            if (mutable) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun approval(ctx: Context, project: String, label: String, tool: String, detail: String) {
        if (visible) return
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("$label: allow $tool?").setContentText(detail.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail.take(600)))
            .setContentIntent(open(ctx)).setAutoCancel(true)
            .addAction(0, "Deny", action(ctx, project, "deny", 1))
            .addAction(0, "Allow", action(ctx, project, "allow", 2)).build()
        runCatching { nm(ctx).notify(id(project), n) }
    }

    fun question(ctx: Context, project: String, label: String, text: String) {
        if (visible) return
        val reply = NotificationCompat.Action.Builder(0, "Answer", action(ctx, project, "answer", 3, mutable = true))
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Your answer").build()).build()
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("$label asks").setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open(ctx)).setAutoCancel(true).addAction(reply).build()
        runCatching { nm(ctx).notify(id(project), n) }
    }

    fun done(ctx: Context, project: String, label: String, summary: String) {
        if (visible) return
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("$label is ready").setContentText(summary.take(140))
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary.take(800)))
            .setContentIntent(open(ctx)).setAutoCancel(true).build()
        runCatching { nm(ctx).notify(id(project), n) }
    }

    fun clear(ctx: Context, project: String) { runCatching { nm(ctx).cancel(id(project)) } }

    internal fun reply(intent: Intent): String? = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
}

class AttentionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val project = intent.getStringExtra("project") ?: return
        val loop = Attention.loops(project) ?: return
        when (intent.action) {
            "allow" -> loop.answerApproval(true)
            "deny" -> loop.answerApproval(false)
            "answer" -> Attention.reply(intent)?.let { loop.answerQuestion(it) }
        }
        Attention.clear(ctx, project)
    }
}
