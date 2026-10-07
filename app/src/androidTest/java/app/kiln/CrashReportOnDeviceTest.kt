package app.kiln

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kiln.build.Project
import app.kiln.device.Warden
import app.kiln.toolchain.Toolchain
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A development build that crashes in real use reports to Kiln, which posts "Fix with Kiln". */
@RunWith(AndroidJUnit4::class)
class CrashReportOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun crashBecomesNotification() = runBlocking<Unit> {
        Graph.init(ctx.applicationContext as android.app.Application)
        // The toolchain comes with the APK; wait for it to be set up (instant when it already is).
        kotlinx.coroutines.runBlocking { Graph.toolchain.syncBundled(Graph.app.assets) }
        // A freshly pushed pack (with the kit's crash hook) replaces the installed one.
        assumeTrue("toolchain not installed", Graph.toolchain.state.value is Toolchain.State.Ready)
        assumeTrue("Warden not ready", Graph.warden.status() == Warden.Status.READY)
        val root = Graph.paths.projects
        File(root, "crashtest").deleteRecursively()
        val p = Project.create(root, "crashtest", "Crash Test", File(Graph.toolchain.templates(), "compose"))
        val pkg = p.meta().`package`
        val nl = System.lineSeparator()
        try {
            p.src.walkTopDown().first { it.name == "MainActivity.kt" }.writeText(listOf(
                "package $pkg", "",
                "import androidx.compose.runtime.Composable",
                "import androidx.compose.runtime.LaunchedEffect",
                "import app.kiln.kit.KilnActivity", "",
                "class MainActivity : KilnActivity() {",
                "    @Composable override fun Content() { LaunchedEffect(Unit) { throw IllegalStateException(\"boom from crashtest\") } }",
                "}").joinToString(nl))
            val r = Graph.builds.build(p)
            assertTrue("build: ${r.diagnostics}", r.ok)
            assertTrue("queries app.kiln", File(p.buildDir, "AndroidManifest.xml").readText().contains("app.kiln"))
            assertTrue(Graph.device.install(File(r.apk!!)).ok)
            Graph.device.launch(pkg)
            val log = File(p.kilnDir, "crashes.log")
            var waited = 0
            while (!log.isFile && waited < 20_000) { delay(500); waited += 500 }
            println("CRASHTEST log=${log.isFile} ${log.takeIf { it.isFile }?.readText()?.take(200)}")
            assertTrue("crash reached Kiln", log.isFile && "boom from crashtest" in log.readText())
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            val n = nm.activeNotifications.firstOrNull { it.notification.extras.getString("android.title") == "Crash Test crashed" }
            println("CRASHTEST notification=${n != null}")
            assertTrue("notification posted", n != null)
            nm.cancel(n!!.id)
        } finally {
            Graph.device.uninstall(pkg)
            p.dir.deleteRecursively()
        }
    }
}
