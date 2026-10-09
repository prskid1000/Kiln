package app.kiln

import app.kiln.build.KotlinFormat
import app.kiln.tools.replaceIgnoringIndent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinFormatTest {
    private fun f(s: String) = KotlinFormat.format(s.trimIndent() + "\n")

    @Test fun `re-indents by bracket depth`() {
        val messy = """
            package a
            fun main() {
              val x = listOf(
                    1,
               2)
                    if (x.isEmpty()) {
            println("empty")
                }
            }
        """
        assertEquals("""
            package a
            fun main() {
                val x = listOf(
                    1,
                    2)
                if (x.isEmpty()) {
                    println("empty")
                }
            }
        """.trimIndent() + "\n", f(messy))
    }

    @Test fun `continuation lines and chained calls`() {
        val messy = """
            val m = Modifier
            .padding(8.dp)
            .fillMaxWidth()
            fun total() =
            items.sum()
        """
        assertEquals("""
            val m = Modifier
                .padding(8.dp)
                .fillMaxWidth()
            fun total() =
                items.sum()
        """.trimIndent() + "\n", f(messy))
    }

    @Test fun `brackets in strings, chars, templates and comments don't count`() {
        val src = """
            fun g() {
            val s = "a { ( [ ${'$'}{x.map { it }} b"
            val c = '{'
            // a { comment
            /* block ( */
            call(s)
            }
        """
        val out = f(src).lines()
        assertEquals("    val c = '{'", out[2])
        assertEquals("    call(s)", out[5])
        assertEquals("}", out[6])
    }

    @Test fun `raw strings are left exactly as written`() {
        val src = "val q = \"\"\"\n  keep   this\n      { as is\n\"\"\".trimIndent()\nfun x() {\ny()\n}\n"
        val out = KotlinFormat.format(src)
        assertEquals("  keep   this", out.lines()[1])
        assertEquals("      { as is", out.lines()[2])
        assertEquals("    y()", out.lines()[5])
    }

    @Test fun `imports sorted and de-duplicated, blank lines collapsed`() {
        val src = "package a\n\nimport b.Z\nimport b.A\nimport b.Z\n\n\n\nfun x() = 1\n\n\n"
        assertEquals("package a\n\nimport b.A\nimport b.Z\n\nfun x() = 1\n", KotlinFormat.format(src))
    }

    @Test fun `unbalanced files are left alone`() {
        val src = "fun x() {\n  y(\n"
        assertEquals(src, KotlinFormat.format(src))
    }

    @Test fun `formatting is idempotent`() {
        val once = f("""
            class A {
            fun b(x: Int) = when (x) {
            1 -> "one"
            else ->
            "many"
            }
            }
        """)
        assertEquals(once, KotlinFormat.format(once))
    }

    @Test fun `real code only changes in whitespace, and settles in one pass`() {
        val files = java.io.File("../kit/src/main/java").walkTopDown().filter { it.extension == "kt" }.toList() +
            java.io.File("src/main/java/app/kiln/tools").walkTopDown().filter { it.extension == "kt" }.toList()
        assert(files.size > 10) { "no sources found" }
        for (file in files) {
            val src = file.readText().replace("\r\n", "\n")
            val out = KotlinFormat.format(src)
            fun content(s: String) = s.lines().map { it.trim() }.filter { it.isNotEmpty() }.sorted()
            assertEquals("${file.name}: only whitespace and import order may change", content(src), content(out))
            assertEquals("${file.name}: formatting twice changes it again", out, KotlinFormat.format(out))
        }
    }

    @Test fun `an edit spanning imports and code matches the code and merges the imports`() {
        // On disk: imports sorted (and one Kiln added); the model remembers its own order and makes a code change.
        val file = "package a\n\nimport b.Added\nimport c.C\nimport d.D\n\nfun f() {\n    val x = 1\n}\n"
        val old = "import d.D\nimport c.C\n\nfun f() {\n    val x = 1\n}"
        val new = "import d.D\nimport e.E\n\nfun f() {\n    val x = 2\n}"
        val out = app.kiln.tools.splitImportEdit(file, old, new)!!
        assertTrue(out, "val x = 2" in out && "val x = 1" !in out)
        assertEquals(listOf("import b.Added", "import d.D", "import e.E"), out.lines().filter { it.startsWith("import") }.sorted())
        assertNull(app.kiln.tools.splitImportEdit(file, old.replace("val x = 1", "val y = 9"), new))   // code part not there
    }

    @Test fun `a whole-file edit that misses says to use write_file and keep the Theme line`() {
        val file = "package a\n\nimport b.B\n\nclass MainActivity : KilnActivity() {\n    override fun Theme(content: @Composable () -> Unit) = KilnTheme(content = content)\n    @Composable\n    override fun Content() { }\n}\n"
        val remembered = "package a\n\nimport b.B\n\nclass MainActivity : KilnActivity() {\n    @Composable\n    override fun Content() { }\n}\n"
        val hint = app.kiln.tools.wholeFileHint(file, remembered)
        assertTrue(hint, "write_file" in hint && "override fun Theme(content: @Composable () -> Unit) = KilnTheme(content = content)" in hint)
        assertEquals("", app.kiln.tools.wholeFileHint(file, "class MainActivity"))    // a small edit: no such hint
    }

    @Test fun `star imports don't indent the next line`() {
        val src = "package a\n\nimport b.*\nimport c.D\n\nfun x() = 1\n"
        assertEquals(src, KotlinFormat.format(src))
    }

    @Test fun `import edits apply whatever order the file has`() {
        val file = "package a\n\nimport b.A\nimport c.C\nimport d.D\n\nfun x() = 1\n"
        val out = app.kiln.tools.importEdit(file, "import d.D\nimport b.A", "import b.A\nimport e.E")!!
        assertEquals(listOf("import b.A", "import c.C", "import e.E"), KotlinFormat.format(out).lines().filter { it.startsWith("import") })
        // The package line as context is fine.
        assertEquals(listOf("import b.A", "import c.C"), app.kiln.tools.importEdit(file, "package a\n\nimport d.D", "package a\n\nimport b.A")!!.lines().filter { it.startsWith("import") })
        assertNull(app.kiln.tools.importEdit(file, "import z.Z", "import y.Y"))          // nothing to remove: not this file's imports
        assertNull(app.kiln.tools.importEdit(file, "fun x() = 1", "fun x() = 2"))         // not an import edit
    }

    @Test fun `edits match regardless of indentation and keep the file's indentation`() {
        val file = "fun a() {\n        if (x) {\n            go()\n        }\n}\n"
        val edited = replaceIgnoringIndent(file, "if (x) {\n    go()\n}", "if (x) {\n    stop()\n}")
        assertEquals("fun a() {\n        if (x) {\n            stop()\n        }\n}\n", edited)
        assertNull(replaceIgnoringIndent(file, "if (y) {", "z"))
    }
}
