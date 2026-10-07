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
        // The toolchain comes with the APK; wait for it to be set up (instant when it already is).
        kotlinx.coroutines.runBlocking { Graph.toolchain.syncBundled(Graph.app.assets) }
        // A fresh app built with the current kit (its inspector is how the hidden display is read).
        val root = Graph.paths.projects
        java.io.File(root, "displaytest").deleteRecursively()
        val project = app.kiln.build.Project.create(root, "displaytest", "Display Test", java.io.File(Graph.toolchain.templates(), "compose"))
        val built = Graph.builds.build(project)
        assertTrue("build: ${built.diagnostics}", built.ok)
        assertTrue(Graph.device.install(java.io.File(built.apk!!)).ok)
        val pkg: String? = project.meta().`package`
        val dev = Device(Graph.warden, TestDisplay(application))
        val mainBefore = Graph.device.foregroundPackage()
        val r = dev.launch(pkg!!)
        println("TESTDISPLAY launch ${r.code} display=${dev.testDisplay?.id()} size=${dev.screenSize()}")
        dev.screenshot()?.let { java.io.File(application.getExternalFilesDir(null), "p2.png").writeBytes(it) }
        val id = dev.testDisplay!!.id()
        val tree = dev.uiTree(pkg)
        println("TESTDISPLAY uiTree(expect) nodes=${tree.size} ${tree.take(4).map { it.label() }}")
        val front = dev.foregroundPackage()
        val shot = dev.screenshot()
        println("TESTDISPLAY front=$front shot=${shot?.size} mainBefore=$mainBefore mainAfter=${Graph.device.foregroundPackage()}")
        // Input goes through the app: a tap must not move focus off the user's screen.
        tree.firstOrNull { it.clickable }?.let { dev.tap(it.cx, it.cy) }
        val focus = Graph.warden.exec(listOf("sh", "-c", "dumpsys window | grep -m1 mTopFocusedDisplayId")).out.trim()
        println("TESTDISPLAY after tap: $focus")
        dev.stop(pkg)
        Graph.device.uninstall(pkg)
        project.dir.deleteRecursively()
        assertTrue("focus stays on the user's screen", focus.endsWith("=0"))
        assertTrue(r.ok)
        assertTrue("tree has the app", tree.isNotEmpty())
        assertTrue("front is the app", front == pkg)
        assertTrue("main screen untouched", Graph.device.foregroundPackage() == mainBefore)
    }
}
