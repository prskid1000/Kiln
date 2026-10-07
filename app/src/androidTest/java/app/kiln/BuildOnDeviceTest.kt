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
 * The whole toolchain, inside the app sandbox (untrusted_app): the components
 * bundled in the APK are set up, then a project from the template builds twice
 * (cold, then warm + incremental).
 */
@RunWith(AndroidJUnit4::class)
class BuildOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun bundledToolchainBuilds() = runBlocking<Unit> {
        Graph.init(ctx.applicationContext as android.app.Application)
        // The toolchain comes with the APK; wait for it to be set up (instant when it already is).
        kotlinx.coroutines.runBlocking { Graph.toolchain.syncBundled(Graph.app.assets) }
        val tc = Graph.toolchain
        assertTrue("bundled toolchain set up: ${tc.state.value}", tc.state.value is Toolchain.State.Ready)
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
        p.dir.deleteRecursively()   // don't leave "Smoke Test" on the user's Projects screen
    }

    /** Only what changed is unpacked: a second sync does nothing; a missing component comes back. */
    @Test fun bundledComponentsSyncByHash() = runBlocking<Unit> {
        Graph.init(ctx.applicationContext as android.app.Application)
        val tc = Graph.toolchain
        val first = tc.syncBundled(Graph.app.assets).getOrThrow()
        val t0 = System.currentTimeMillis()
        assertEquals(first, tc.syncBundled(Graph.app.assets).getOrThrow())
        val idle = System.currentTimeMillis() - t0
        println("SYNC idle ${idle}ms set=$first")
        assertTrue("an up-to-date sync only reads headers (${idle}ms)", idle < 5_000)
        // Lose a component (as if a new APK changed it): exactly that one is installed again.
        val templates = File(Graph.paths.toolchainRoot, "c/templates").listFiles()!!.single()
        templates.deleteRecursively()
        assertEquals(first, tc.syncBundled(Graph.app.assets).getOrThrow())
        assertTrue(File(templates, "component.json").isFile)
        assertTrue(File(tc.templates(), "compose").isDirectory)
        println("SYNC root=${Graph.paths.toolchainRoot.list()!!.sorted()}")
        assertEquals(listOf("c", "current", "sets"), Graph.paths.toolchainRoot.list()!!.sorted())
    }
}
