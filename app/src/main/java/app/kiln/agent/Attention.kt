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
    /** What's on screen: whether Kiln is visible, and whose chat. Prompt watchers follow it (see [screen]). */
    val screen = kotlinx.coroutines.flow.MutableStateFlow<Pair<Boolean, String?>>(false to null)
    /** Set by MainActivity: when Kiln is on screen, the in-app cards are enough. */
    var visible: Boolean
        get() = screen.value.first
        set(v) { screen.value = v to screen.value.second }
    /** The project whose chat is on screen: only its prompts can be answered in the app, so only its are skipped. */
    var onScreen: String?
        get() = screen.value.second
        set(v) { screen.value = screen.value.first to v }

    /** Its prompts are on screen: its own chat, or (for a best-of attempt) its parent's chat, where they show. */
    private fun shown(project: String): Boolean {
        val on = onScreen ?: return false
        return visible && (project == on || Attempts.ownerOf(app.kiln.build.Project(java.io.File(app.kiln.Graph.paths.projects, project))) == on)
    }

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).also {
        if (it.getNotificationChannel(CHANNEL) == null)
            it.createNotificationChannel(NotificationChannel(CHANNEL, "Needs you", NotificationManager.IMPORTANCE_HIGH))
    }
    private fun id(project: String) = 1000 + (project.hashCode() and 0xfff)

    // Opens that project (its own request code, so each notification keeps its project).
    private fun open(ctx: Context, project: String) = PendingIntent.getActivity(ctx, id(project) * 4,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // An attempt's notification opens its parent (the copy isn't somewhere to keep working: it's deleted later).
            .putExtra(MainActivity.EXTRA_PROJECT, Attempts.ownerOf(app.kiln.build.Project(java.io.File(app.kiln.Graph.paths.projects, project))) ?: project),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    // The request code is per project: Android matches PendingIntents ignoring extras, so one shared code made
    // project A's Allow carry project B (FLAG_UPDATE_CURRENT replaced it) and approve the wrong request.
    // [key] names the request the buttons answer: a stale notification must not answer a later, different one.
    private fun action(ctx: Context, project: String, what: String, req: Int, key: String, mutable: Boolean = false) =
        PendingIntent.getBroadcast(ctx, id(project) * 4 + req, Intent(ctx, AttentionReceiver::class.java).setAction(what)
            .putExtra("project", project).putExtra("key", key),
            if (mutable) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun approval(ctx: Context, project: String, label: String, tool: String, detail: String) {
        if (shown(project)) return
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground).setOnlyAlertOnce(true)
            .setContentTitle("$label: allow $tool?").setContentText(detail.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail.take(600)))
            .setContentIntent(open(ctx, project)).setAutoCancel(true)
            .addAction(0, "Deny", action(ctx, project, "deny", 1, key(tool, detail)))
            .addAction(0, "Allow", action(ctx, project, "allow", 2, key(tool, detail))).build()
        runCatching { nm(ctx).notify(project, PROMPT_ID, n) }
    }

    /** [answerable]: a plain question gets an inline reply; a card (several questions, the look picker) opens Kiln. */
    fun question(ctx: Context, project: String, label: String, text: String, answerable: Boolean = true) {
        if (shown(project)) return
        val reply = NotificationCompat.Action.Builder(0, "Answer", action(ctx, project, "answer", 3, key(text), mutable = true))
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Your answer").build()).build()
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground).setOnlyAlertOnce(true)
            .setContentTitle("$label asks").setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open(ctx, project)).setAutoCancel(true).apply { if (answerable) addAction(reply) }.build()
        runCatching { nm(ctx).notify(project, PROMPT_ID, n) }
    }

    fun done(ctx: Context, project: String, label: String, summary: String) {
        if (shown(project)) return
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground).setOnlyAlertOnce(true)
            .setContentTitle("$label is ready").setContentText(summary.take(140))
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary.take(800)))
            .setContentIntent(open(ctx, project)).setAutoCancel(true).build()
        runCatching { nm(ctx).notify(project, PROMPT_ID, n) }
    }

    internal fun key(vararg parts: String) = parts.joinToString("|").hashCode().toString()

    // Tagged by project: a hashed id could be shared, and clearing one project's notification removed another's live prompt.
    fun clear(ctx: Context, project: String) { runCatching { nm(ctx).cancel(project, PROMPT_ID) } }
    private const val PROMPT_ID = 1000

    internal fun reply(intent: Intent): String? = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
}

class AttentionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val project = intent.getStringExtra("project") ?: return
        val loop = Attention.loops(project) ?: return
        val key = intent.getStringExtra("key")
        when (intent.action) {
            "allow", "deny" -> loop.approval.value?.takeIf { Attention.key(it.tool, it.input) == key }?.let { loop.answerApproval(intent.action == "allow") }
            "answer" -> loop.question.value?.takeIf { it.kind == "text" && Attention.key(it.text) == key }?.let { Attention.reply(intent)?.let { r -> loop.answerQuestion(r) } }
        }
        Attention.clear(ctx, project)
    }
}
