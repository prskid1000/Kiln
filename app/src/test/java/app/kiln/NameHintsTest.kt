package app.kiln

import app.kiln.build.BuildResult
import app.kiln.build.Diagnostic
import app.kiln.build.Project
import app.kiln.tools.NameCheck
import app.kiln.tools.ProjectSymbols
import app.kiln.tools.errorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Build errors about the model's own names say what it actually wrote, with signatures. */
class NameHintsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun project(): Project {
        val dir = tmp.newFolder("app1")
        File(dir, "kiln.json").writeText("""{"package":"kiln.app.app1","label":"app1","versionCode":1,"versionName":"1.0","minSdk":30,"targetSdk":36,"permissions":[]}""")
        File(dir, "src/kiln/app/app1/data").mkdirs()
        File(dir, "src/kiln/app/app1/data/Repo.kt").writeText("""
            package kiln.app.app1.data

            // fun notAFunction() in a comment
            data class Settings(val budget: Double = 0.0, val currency: String = "INR")

            object Repo {
                val expenses = listOf<Int>()
                fun monthTotal(month: Int): Double {
                    fun local() = 1   // local: not a member
                    val inner = 2
                    return 0.0
                }
            }

            fun formatMoney(amount: Double, currency: String): String = "x"
            fun budgetLeft(spent: Double) = 100.0 - spent
            fun Repo.byCategory(): Map<String, Double> = emptyMap()
        """.trimIndent())
        return Project(dir)
    }

    private fun fail(project: Project, message: String, source: String) =
        errorHints(BuildResult(ok = false, diagnostics = listOf(Diagnostic("error", message, "src/kiln/app/app1/ui/Home.kt", 3, 1, "kotlinc", source))), project)

    @Test fun indexesDeclarationsWithSignatures() {
        val s = ProjectSymbols.of(project())
        assertEquals("fun budgetLeft(spent: Double) = 100.0 - spent", s.named("budgetLeft").single().signature)
        assertEquals("fun formatMoney(amount: Double, currency: String): String", s.named("formatMoney").single().signature)
        assertEquals(setOf("expenses", "monthTotal", "byCategory"), s.members("Repo").map { it.name }.toSet())
        assertEquals(setOf("budget", "currency"), s.members("Settings").map { it.name }.toSet())
        assertTrue(s.named("local").isEmpty() && s.named("inner").isEmpty() && s.named("notAFunction").isEmpty())
    }

    @Test fun misspeltOwnFunctionGetsItsSignature() {
        val h = fail(project(), "unresolved reference 'formatMony'.", "> Text(formatMony(x, \"INR\"))")
        assertTrue(h, "fun formatMoney(amount: Double, currency: String): String" in h)
    }

    @Test fun missingObjectMemberListsWhatItHas() {
        val h = fail(project(), "unresolved reference 'currentMonthTotal'.", "> val t = Repo.currentMonthTotal()")
        assertTrue(h, "Repo has no `currentMonthTotal`" in h)
        assertTrue(h, "fun monthTotal(month: Int): Double" in h)
        assertTrue(h, "byCategory" in h)
    }

    @Test fun missingDataClassFieldListsItsProperties() {
        val h = fail(project(), "unresolved reference 'monthlyBudget' on receiver of type 'Settings'.", "> s.monthlyBudget")
        assertTrue(h, "Settings has no `monthlyBudget`" in h && "budget" in h)
    }

    @Test fun argumentOfInnerCallIsBlamedOnTheInnerCall() {
        val p = project()
        val src = "        keyboard = KeyboardOptions(keyboard = KeyboardType.Decimal),"
        File(p.dir, "src/kiln/app/app1/ui").mkdirs()
        File(p.dir, "src/kiln/app/app1/ui/Home.kt").writeText("package kiln.app.app1.ui\n\nfun f() {\n    KTextField(\n        value = \"\",\n$src\n    )\n}\n")
        fun hint(msg: String, col: Int) = errorHints(BuildResult(ok = false, diagnostics = listOf(
            Diagnostic("error", msg, "src/kiln/app/app1/ui/Home.kt", 6, col, "kotlinc", "> $src"))), p)
        // "no parameter 'keyboard'" at the argument inside KeyboardOptions(…); "no value passed" at the KeyboardOptions call.
        assertTrue(hint("no parameter with name 'keyboard' found.", src.indexOf("keyboard =", 30) + 1).contains("(KeyboardOptions)"))
        assertTrue(hint("no value passed for parameter 'autoCorrect'.", src.indexOf("KeyboardOptions") + 1).contains("(KeyboardOptions)"))
    }

    @Test fun reviewCases() {
        // Import replacement is whole-line: LocalDateTime is left alone; an alias is kept.
        val t = "import kotlinx.datetime.LocalDate\nimport kotlinx.datetime.LocalDateTime\nimport app.kiln.kit.ui.KButton as Btn\nx\n"
        val r = NameCheck.replaceImport(NameCheck.replaceImport(t, "kotlinx.datetime.LocalDate", "java.time.LocalDate"), "app.kiln.kit.ui.KButton", "app.kiln.kit.KButton")
        assertEquals("import java.time.LocalDate\nimport kotlinx.datetime.LocalDateTime\nimport app.kiln.kit.KButton as Btn\nx\n", r)
        // One-line constructor: every property; Foo::class isn't a type; nested generics and nullable receivers are found.
        val src = "data class Expense(val id: Long, val title: String, val amount: Double = 0.0)\nval k = Foo::class\nfun load() { val total = 1 }\n" +
            "fun <T : Comparable<T>> top(xs: List<T>): T = xs.max()\nfun String?.orDash(): String = this ?: \"-\"\nval c = '('\nval after: Int = 2\n"
        val s = ProjectSymbols.parse(NameCheck.codeOnly(src), src, "a.kt")
        assertEquals(listOf("id", "title", "amount"), s.filter { it.owner == "Expense" }.map { it.name })
        assertEquals("val amount: Double = 0.0", s.single { it.name == "amount" }.signature)
        assertTrue(s.none { it.name == "fun" || it.name == "total" })
        assertTrue(s.any { it.name == "top" } && s.single { it.name == "orDash" }.owner == "String")
        assertTrue(s.any { it.name == "after" })   // a '(' char literal doesn't hide later properties
    }

    @Test fun templateExpressionsAreCode() {
        val c = NameCheck.codeOnly("val s = \"Total: \${KFormat.money(t)} and KSlide text\"")
        assertTrue(c, "KFormat.money(t)" in c)
        assertFalse(c, "KSlide" in c)
    }

    @Test fun helpers() {
        assertEquals(1, NameCheck.distance("kolnscreen", "kilnscreen"))
        val code = NameCheck.codeOnly("val a = \"KSlide\" // KSlide\nKSlide()")
        assertFalse(code.substringBefore('\n').contains("KSlide"))
        assertTrue(code.substringAfter('\n').contains("KSlide"))
        assertEquals("import app.kiln.kit.rememberKToast\nx", NameCheck.replaceImport("import app.kiln.kit.Koln.kit.rememberKToast\nx", "app.kiln.kit.Koln.kit.rememberKToast", "app.kiln.kit.rememberKToast"))
        assertEquals("KilnTabs(KilnTabsX) \"KolnTabs\"", NameCheck.rename("KolnTabs(KilnTabsX) \"KolnTabs\"", "KolnTabs", "KilnTabs").let { it })
    }
}
