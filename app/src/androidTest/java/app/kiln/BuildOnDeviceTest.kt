package app.kiln

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kiln.build.Project
import app.kiln.toolchain.Toolchain
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The whole toolchain, inside the app sandbox (untrusted_app): import the pack
 * from the inbox (adb push it to /sdcard/Android/data/app.kiln/files/), create
 * a project from the template, build it twice (cold, then warm + incremental).
 */
@RunWith(AndroidJUnit4::class)
class BuildOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun importPackAndBuild() = runBlocking {
        Graph.init(ctx.applicationContext as android.app.Application)
        val tc = Graph.toolchain
        if (tc.state.value !is Toolchain.State.Ready) {
            val zip = tc.inboxPack()
            assumeTrue("push kiln-toolchain-*.zip to the app's external files dir", zip != null)
            val r = tc.install(zip!!.inputStream(), zip.length())
            assertTrue("install: ${r.exceptionOrNull()}", r.isSuccess)
        }
        val root = Graph.paths.projects
        File(root, "smoketest").deleteRecursively()
        val p = Project.create(root, "smoketest", "Smoke Test", File(tc.templates(), "compose"))
        val cold = Graph.builds.build(p)
        println("COLD ${cold.totalMs}ms ${cold.steps}")
        assertTrue("cold build: ${cold.diagnostics}", cold.ok)
        assertTrue(File(cold.apk!!).length() > 100_000)

        // Edit one source file: only kotlinc / d8 / package / sign rerun.
        val main = p.src.walkTopDown().first { it.name == "MainActivity.kt" }
        main.writeText(main.readText().replace("Built on this phone with Kiln.", "Rebuilt warm."))
        val warm = Graph.builds.build(p)
        println("WARM ${warm.totalMs}ms ${warm.steps}")
        assertTrue("warm build: ${warm.diagnostics}", warm.ok)
        assertEquals(true, warm.steps.first { it.step == "aapt2-link" }.skipped)

        // A broken file comes back as a structured diagnostic with file and line.
        main.writeText(main.readText().replace("Rebuilt warm.\"", "Rebuilt warm.\" + undefinedThing"))
        val broken = Graph.builds.build(p, checkOnly = true)
        println("BROKEN ${broken.diagnostics}")
        assertTrue(!broken.ok)
        val e = broken.errors.first()
        assertTrue(e.file!!.endsWith("MainActivity.kt") && e.line != null)
    }
}
