package app.kiln

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kiln.build.BuildEngine
import app.kiln.build.Project
import app.kiln.toolchain.Toolchain
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Release builds: a non-debuggable APK and a Play bundle, copied out for checking with bundletool. */
@RunWith(AndroidJUnit4::class)
class ReleaseOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun releaseApkAndBundle() = runBlocking<Unit> {
        Graph.init(ctx.applicationContext as android.app.Application)
        assumeTrue("toolchain not installed", Graph.toolchain.state.value is Toolchain.State.Ready)
        val root = Graph.paths.projects
        File(root, "releasetest").deleteRecursively()
        val p = Project.create(root, "releasetest", "Release Test", File(Graph.toolchain.templates(), "compose"))
        try {
            val out = ctx.getExternalFilesDir(null)!!
            for (kind in listOf(BuildEngine.Kind.RELEASE_APK, BuildEngine.Kind.RELEASE_AAB)) {
                val r = Graph.builds.build(p, kind = kind)
                println("RELEASE $kind ok=${r.ok} ${r.apk} ${r.errors}")
                assertTrue("$kind: ${r.diagnostics}", r.ok)
                File(r.apk!!).copyTo(File(out, "release-test." + File(r.apk!!).extension), overwrite = true)
            }
            // Developing again afterwards still gives a debuggable build.
            assertTrue(Graph.builds.build(p).ok)
        } finally { p.dir.deleteRecursively() }
    }
}
