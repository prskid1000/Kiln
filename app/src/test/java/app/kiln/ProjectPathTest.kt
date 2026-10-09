package app.kiln

import app.kiln.build.Project
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A path written with the project's own name in front still means the file in the project. */
class ProjectPathTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun projectNamePrefixIsDroppedOnlyWhenItDoesNotExist() {
        val dir = tmp.newFolder("expense3")
        File(dir, "src/kiln").mkdirs(); File(dir, "kiln.json").writeText("{}")
        val p = Project(dir)
        assertEquals("kiln.json", p.normalize("expense3/kiln.json"))
        assertEquals("src/**/*.kt", p.normalize("expense3/src/**/*.kt"))
        assertEquals("", p.normalize("expense3"))
        assertEquals(File(dir, "kiln.json").canonicalFile, p.resolve("expense3/kiln.json"))
        // A real folder of that name inside the project is left alone.
        File(dir, "expense3").mkdirs()
        assertEquals("expense3/x", p.normalize("expense3/x"))
        assertEquals("src/kiln", p.normalize("./src/kiln"))
    }
}
