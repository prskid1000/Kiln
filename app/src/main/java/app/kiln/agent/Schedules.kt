package app.kiln.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import app.kiln.Graph
import app.kiln.build.Project
import app.kiln.core.KJ
import app.kiln.ui.KilnVM
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Calendar

/** A run Kiln starts by itself every day, e.g. "test the main flows and fix anything broken". */
@Serializable
data class Schedule(
    val prompt: String,
    val hour: Int = 2,
    val minute: Int = 0,
    val enabled: Boolean = true,
    /** Wait for the phone to be charging (a run can take a while and use the CPU hard). */
    val chargingOnly: Boolean = true,
    val lastRun: Long = 0,
    val lastResult: String = "",
)

/**
 * Daily scheduled runs. Each project with a schedule has an exact alarm at its time; when it
 * fires, the run starts in a new chat (an exact alarm may start the foreground service from the
 * background, which a job may not). Alarms are re-armed after each run, at boot and at app start.
 */
object Schedules {
    private fun file(p: Project) = File(p.kilnDir, "schedule.json")

    fun get(p: Project): Schedule? = runCatching { KJ.decodeFromString(Schedule.serializer(), file(p).readText()) }.getOrNull()

    fun set(ctx: Context, p: Project, s: Schedule?) {
        if (s == null) file(p).delete()
        else { p.kilnDir.mkdirs(); file(p).writeText(KJ.encodeToString(Schedule.serializer(), s)) }
        arm(ctx, p.name, s)
    }

    /** The next time [s] fires after [now]. */
    fun next(s: Schedule, now: Long = System.currentTimeMillis()): Long = Calendar.getInstance().run {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, s.hour); set(Calendar.MINUTE, s.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        timeInMillis
    }

    private fun intent(ctx: Context, name: String) = PendingIntent.getBroadcast(ctx, name.hashCode(),
        Intent(ctx, ScheduleReceiver::class.java).setAction(ACTION).putExtra("project", name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun arm(ctx: Context, name: String, s: Schedule?) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (s == null || !s.enabled) { am.cancel(intent(ctx, name)); return }
        val at = next(s)
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(ctx, name))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(ctx, name))
    }

    /** Every project's alarm, from its schedule file (after boot, an update, or app start). */
    fun armAll(ctx: Context) {
        Graph.paths.projects.listFiles()?.filter { File(it, "kiln.json").isFile }?.forEach { d ->
            arm(ctx, d.name, get(Project(d)))
        }
    }

    const val ACTION = "app.kiln.SCHEDULED_RUN"
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Graph.init(context.applicationContext as android.app.Application)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Schedules.armAll(context)
                    Schedules.ACTION -> run(context, intent.getStringExtra("project") ?: return@launch)
                }
            } finally { pending.finish() }
        }
    }

    private suspend fun run(ctx: Context, name: String) {
        val dir = File(Graph.paths.projects, name).takeIf { File(it, "kiln.json").isFile } ?: return
        val p = Project(dir)
        val s = Schedules.get(p)?.takeIf { it.enabled } ?: return
        val charging = ctx.getSystemService(BatteryManager::class.java).isCharging
        // The toolchain may still be unpacking after an update. Waiting for it here could outlast the broadcast's time
        // limit (the process killed, the run lost, tomorrow's alarm never re-armed): skip tonight and say why.
        val toolsReady = Graph.toolchain.state.value is app.kiln.toolchain.Toolchain.State.Ready
        val result = when {
            !toolsReady -> "skipped — the build tools were updating"
            s.chargingOnly && !charging -> "skipped — the phone wasn't charging"
            else -> KilnVM.startRun(ctx, name, s.prompt, freshChat = true) ?: "started"
        }
        Schedules.set(ctx, p, s.copy(lastRun = System.currentTimeMillis(), lastResult = result))   // also re-arms tomorrow's
    }
}
