package app.kiln

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kiln.agent.Schedule
import app.kiln.agent.Schedules
import app.kiln.build.Project
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Calendar

/** The daily alarm really fires, applies "only while charging", records the result and re-arms. */
@RunWith(AndroidJUnit4::class)
class ScheduleOnDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private fun shell(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).close()

    @Test fun alarmFiresAndSkipsWhenNotCharging() = runBlocking<Unit> {
        Graph.init(ctx.applicationContext as android.app.Application)
        kotlinx.coroutines.runBlocking { Graph.toolchain.syncBundled(Graph.app.assets) }
        val root = Graph.paths.projects
        File(root, "scheduletest").deleteRecursively()
        val p = Project.create(root, "scheduletest", "Schedule Test", File(Graph.toolchain.templates(), "compose"))
        shell("dumpsys battery unplug")
        try {
            delay(1500)
            val at = Calendar.getInstance().apply { add(Calendar.MINUTE, 1) }
            Schedules.set(ctx, p, Schedule("say hi", at.get(Calendar.HOUR_OF_DAY), at.get(Calendar.MINUTE), chargingOnly = true))
            var waited = 0
            while ((Schedules.get(p)?.lastRun ?: 0L) == 0L && waited < 100_000) { delay(1000); waited += 1000 }
            val s = Schedules.get(p)
            println("SCHEDULE waited=${waited}ms result=${s?.lastResult}")
            assertTrue("alarm fired", (s?.lastRun ?: 0L) > 0)
            assertTrue(s!!.lastResult.contains("charging"))
        } finally {
            shell("dumpsys battery reset")
            Schedules.set(ctx, p, null)
            p.dir.deleteRecursively()
        }
    }
}
