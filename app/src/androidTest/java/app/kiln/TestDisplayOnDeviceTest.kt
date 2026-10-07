package app.kiln

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kiln.device.Device
import app.kiln.device.TestDisplay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The agent's hidden test display with the real classes: launch an installed Kiln app there,
 * read its UI tree, tap it, screenshot it — without touching the user's screen.
 */
@RunWith(AndroidJUnit4::class)
class TestDisplayOnDeviceTest {
    private val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application

    @Test fun agentTestsOnHiddenDisplay() = runBlocking<Unit> {
        Graph.init(application)
        val pkg = Graph.device.installedApps().let { a -> a.firstOrNull { it.contains("water") } ?: a.firstOrNull() }
        assumeTrue("needs an installed Kiln app", pkg != null)
        val dev = Device(Graph.warden, TestDisplay(application))
        val mainBefore = Graph.device.foregroundPackage()
        val r = dev.launch(pkg!!)
        println("TESTDISPLAY launch ${r.code} display=${dev.testDisplay?.id()} size=${dev.screenSize()}")
        dev.screenshot()?.let { java.io.File(application.getExternalFilesDir(null), "p2.png").writeBytes(it) }
        val id = dev.testDisplay!!.id()
        val raw = Graph.warden.exec(listOf("sh", "-c", "uiautomator dump --display $id /data/local/tmp/p2.xml >/dev/null; cat /data/local/tmp/p2.xml")).out
        val pkgs = Regex("package=.([a-z0-9._]+)").findAll(raw).map { it.groupValues[1] }.toSet()
        println("TESTDISPLAY raw dump packages=$pkgs")
        val tree = dev.uiTree(pkg)
        println("TESTDISPLAY uiTree(expect) nodes=${tree.size} ${tree.take(4).map { it.label() }}")
        val front = dev.foregroundPackage()
        val shot = dev.screenshot()
        println("TESTDISPLAY front=$front shot=${shot?.size} mainBefore=$mainBefore mainAfter=${Graph.device.foregroundPackage()}")
        dev.stop(pkg)
        assertTrue(r.ok)
        assertTrue("tree has the app", tree.isNotEmpty())
        assertTrue("front is the app", front == pkg)
        assertTrue("main screen untouched", Graph.device.foregroundPackage() == mainBefore)
    }
}
