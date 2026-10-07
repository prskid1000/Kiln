package app.kiln

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kiln.build.Project
import app.kiln.toolchain.Toolchain
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Every skill recipe the agent can load must compile against the real kit, as written. */
@RunWith(AndroidJUnit4::class)
class SkillsOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun everySkillCompiles() = runBlocking<Unit> {
        Graph.init(ctx.applicationContext as android.app.Application)
        // The toolchain comes with the APK; wait for it to be set up (instant when it already is).
        kotlinx.coroutines.runBlocking { Graph.toolchain.syncBundled(Graph.app.assets) }
        val tc = Graph.toolchain
        assumeTrue("toolchain not installed", tc.state.value is Toolchain.State.Ready)
        val root = Graph.paths.projects
        File(root, "skilltest").deleteRecursively()
        val p = Project.create(root, "skilltest", "Skill Test", File(tc.templates(), "compose"))
        val pkg = p.meta().`package`
        val dir = File(p.src, pkg.replace('.', '/'))
        val failures = mutableListOf<String>()
        try {
            for (name in ctx.assets.list("skills")!!.filter { it.endsWith(".md") }.sorted()) {
                val md = ctx.assets.open("skills/$name").bufferedReader().readText()
                val code = md.substringAfter("```kotlin").substringBefore("```").trim()
                val f = File(dir, "Skill.kt")
                f.writeText("package $pkg" + System.lineSeparator() + System.lineSeparator() + code + System.lineSeparator())
                val r = Graph.builds.build(p, checkOnly = true)
                println("SKILL $name ok=${r.ok} ${r.errors.take(3)}")
                if (!r.ok) failures += "$name: ${r.errors.take(3)}"
                f.delete()
            }
        } finally { p.dir.deleteRecursively() }
        assertTrue("skills that don't compile:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
