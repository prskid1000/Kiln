package app.kiln

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kiln.agent.Attachment
import app.kiln.agent.Attachments
import app.kiln.build.Project
import app.kiln.core.parseJson
import app.kiln.device.Warden
import app.kiln.toolchain.Toolchain
import app.kiln.tools.SessionState
import app.kiln.tools.Tool
import app.kiln.tools.ToolContext
import app.kiln.tools.ToolResult
import app.kiln.tools.validateInput
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Every registered tool, called the way a session calls it (schema validation, then run) on a
 * fresh project and the real device through Warden. Inputs include the sloppy shapes models
 * actually send (`class X`, `Button "Label"`, level "error", null optionals, action "save").
 *
 *   adb shell am instrument -w -e class app.kiln.ToolsOnDeviceTest app.kiln.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Prints one TOOLTEST line per call; fails listing every call that didn't behave.
 */
@RunWith(AndroidJUnit4::class)
class ToolsOnDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application

    private data class Case(val tool: String, val input: String, val expectError: Boolean = false, val expect: String? = null,
                            val images: Boolean = false, val absent: String? = null)

    /** Chat attachments: images become image blocks, text inlines, binaries are noted — all saved in the project. */
    @Test fun attachmentsBecomeBlocks() {
        Graph.init(app)
        // The toolchain comes with the APK; wait for it to be set up (instant when it already is).
        kotlinx.coroutines.runBlocking { Graph.toolchain.syncBundled(Graph.app.assets) }
        assumeTrue("toolchain installed", Graph.toolchain.state.value is Toolchain.State.Ready)
        File(Graph.paths.projects, "attachtest").deleteRecursively()
        val p = Project.create(Graph.paths.projects, "attachtest", "Attach Test", File(Graph.toolchain.templates(), "compose"))
        val png = java.io.ByteArrayOutputStream().also {
            android.graphics.Bitmap.createBitmap(40, 30, android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val blocks = Attachments.blocks(p, listOf(
            Attachment("mock.png", "image/png", png),
            Attachment("data.csv", "text/csv", "name,qty\nmilk,2\n".toByteArray()),
            Attachment("font.ttf", "font/ttf", ByteArray(2048) { (it % 7).toByte() }),
        )).map { it as JsonObject }
        println("ATTACH ${blocks.map { it["type"] }}")
        // An image comes with a note of where it was saved, then the picture.
        assertTrue(blocks[0].toString().contains("attachments/mock.png"))
        assertTrue(blocks[1]["type"].toString() == "\"image\"")
        assertTrue(blocks[2].toString().contains("milk,2") && blocks[2].toString().contains("attachments/data.csv"))
        assertTrue(blocks[3].toString().contains("binary file saved") && blocks[3].toString().contains("attachments/font.ttf"))
        assertTrue(File(p.dir, "attachments/mock.png").isFile && File(p.dir, "attachments/font.ttf").length() == 2048L)
        p.dir.deleteRecursively()
    }

    @Test fun everyToolWorks() = runBlocking {
        Graph.init(app)
        // The toolchain comes with the APK; wait for it to be set up (instant when it already is).
        kotlinx.coroutines.runBlocking { Graph.toolchain.syncBundled(Graph.app.assets) }
        assumeTrue("toolchain installed", Graph.toolchain.state.value is Toolchain.State.Ready)
        val root = Graph.paths.projects
        File(root, "tooltest").deleteRecursively()
        val p = Project.create(root, "tooltest", "Tool Test", File(Graph.toolchain.templates(), "compose"))
        val src = "src/kiln/app/tooltest"
        val tools = Graph.kiln.tools(p).second.associateBy { it.name }
        val device = Graph.warden.status() == Warden.Status.READY
        val state = SessionState()
        val ctx = object : ToolContext {
            override val project = p
            override val sessionId = "tooltest"
            override val state = state
            override val spillDir = File(Graph.paths.spill, "tooltest").apply { mkdirs() }
            override fun progress(line: String) {}
            override suspend fun ask(question: String, options: List<String>) = options.firstOrNull() ?: "yes"
            override fun spill(text: String, maxChars: Int) = if (text.length <= maxChars) text else text.take(maxChars) + "\n…"
        }

        // A mock-up to compare against.
        File(p.dir, "attachments/target.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            android.graphics.Bitmap.createBitmap(90, 160, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(22, 24, 38)) }
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        val files = listOf(
            Case("project_info", "{}", expect = "kiln.app.tooltest"),
            Case("list_dir", """{"path":""}""", expect = "kiln.json"),
            Case("list_dir", """{"path":"src"}""", expect = "MainActivity.kt"),
            Case("list_dir", """{"path":"/"}""", expect = "kiln.json"),
            Case("glob", """{"pattern":"**/*.kt"}""", expect = "MainActivity.kt"),
            Case("grep", """{"pattern":"KilnScreen","glob":"","context":0}""", expect = "MainActivity.kt"),
            Case("grep", """{"pattern":"KilnScreen"}""", expect = "MainActivity.kt"),
            Case("read_file", """{"path":"$src/MainActivity.kt","offset":0,"limit":50}""", expect = "class MainActivity"),
            Case("read_file", """{"path":"$src/MainActivity.kt","offset":null,"limit":null}""", expect = "class MainActivity"),
            Case("read_file", """{"path":"./kiln.json"}""", expect = "tooltest"),
            Case("read_file", """{"path":"nope.kt"}""", expectError = true),
            Case("read_file", """{"path":"../../secrets/x"}""", expectError = true, expect = "escapes"),
            Case("write_file", """{"path":"$src/Extra.kt","content":"package kiln.app.tooltest\n\nval greeting = \"hi\"\n"}""", expect = "Extra.kt"),
            Case("read_file", """{"path":"$src/Extra.kt"}""", expect = "greeting"),
            Case("edit_file", """{"path":"$src/Extra.kt","old_text":"\"hi\"","new_text":"\"hello\""}""", expect = "edited"),
            Case("edit_file", """{"path":"$src/Extra.kt","old_text":"not there","new_text":"x"}""", expectError = true),
            Case("multi_edit", """{"path":"$src/Extra.kt","edits":[{"old_text":"greeting","new_text":"salute"},{"old_text":"\"hello\"","new_text":"\"hey\""}]}""", expect = "Extra.kt"),
            Case("move", """{"from":"$src/Extra.kt","to":"$src/Extra2.kt"}"""),
            Case("delete", """{"path":"$src/Extra2.kt"}"""),
            Case("set_app_meta", """{"label":"Tool Test","version_name":"1.1"}"""),
            Case("project_memory", """{"action":"write","content":"uses KStore"}""", expect = "saved"),
            Case("project_memory", """{"action":"save","content":"uses KStore; tested"}""", expectError = true), // enum: model must retry with write
            Case("project_memory", """{"action":"read","content":""}""", expect = "uses KStore"),
            Case("todo", """{"items":[{"text":"a","status":"done"},{"text":"b","status":"in_progress"}]}""", expect = "[x] a"),
            Case("todo", """{"items":[{"text":"a","status":"finished"}]}""", expectError = true),
            Case("checkpoint", """{"label":"before-test"}""", expect = "saved"),
            Case("restore", """{"id":""}""", expect = "before-test"),
            Case("ask_user", """{"questions":[{"question":"Which colour?","header":"Colour","options":[{"label":"Accent","description":"a"},{"label":"Ok","description":"b"}]}]}""", expect = "Accent"),
            Case("sdk_lookup", """{"query":"class KStore"}""", expect = "app.kiln.kit.KStore"),
            Case("sdk_lookup", """{"query":"KStore","member":"*"}""", expect = "getState"),
            Case("sdk_lookup", """{"query":"app.kiln.kit.KListRow"}""", expect = "part of the Kiln kit"),   // run 14
            Case("sdk_lookup", """{"query":"KStore","member":"KStore"}""", expect = "constructor"),
            Case("sdk_lookup", """{"query":"app.kiln.kit KStore","member":"update"}""", expect = "update"),
            Case("sdk_lookup", """{"query":"KilnActivity onCreate"}""", expect = "fun onCreate"),
            Case("sdk_lookup", """{"query":"KilnActivity","member":"setContent"}""", expect = "inherited from"),
            Case("sdk_lookup", """{"query":"Icons.Filled.Home"}""", expect = "Home"),
            Case("sdk_lookup", """{"query":"CameraCharacteristics","member":"FLASHLIGHT"}""", expect = "similar"),
            Case("sdk_lookup", """{"query":"Modifier.padding"}""", expect = "padding"),
            Case("sdk_lookup", """{"query":"Zzqqxx"}""", expectError = true),
            Case("kit_search", """{"query":"App icon"}""", expect = "ic_launcher"),
            Case("kit_search", """{"query":"glassmorphism"}""", expect = "KStyle"),
            Case("choose_look", """{"app":"a notes app"}""", expect = "Kiln's default"),
            Case("ask_user", """{"questions":[{"question":"Which layout?","header":"Layout","options":[{"label":"List (Recommended)","description":"Rows"},{"label":"Grid","description":"Tiles","preview":"[] []"}]},{"question":"Sync?","header":"Sync","multiSelect":true,"options":[{"label":"Cloud","description":"x"},{"label":"Local","description":"y"}]}]}""", expect = "\"Which layout?\" = \"List (Recommended)\""),
            Case("ask_user", """{"question":"Ok?","options":["Yes","No"]}""", expectError = true),
            Case("kit_search", """{"query":"KStore"}""", expect = "class KStore"),
            Case("kit_search", """{"query":"swipe to delete"}""", expect = "KSwipeRow"),
            Case("kit_search", """{"query":"KHttp"}""", expect = "fun delete"),
            Case("kit_search", """{"query":"osmdroid version"}""", expect = "osmdroid-android:6.1.20"),
            Case("make_graphic", """{"svg":"<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'><rect x='2' y='2' width='20' height='20' rx='4' fill='#8B7CF6'/><circle cx='12' cy='12' r='5' fill='white' opacity='0.8'/></svg>","path":"res/drawable/ic_test.xml"}""", expect = "vector drawable"),
            Case("make_graphic", """{"svg":"<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'><circle cx='5' cy='5' r='4' fill='red'/></svg>","path":"assets/dot.png","size":64}""", expect = "PNG 64px"),
            // Run 12: an Android vector (not SVG) with xmlns="android=…" — repaired, written as given, previewed.
            Case("make_graphic", """{"svg":"<vector xmlns=\"android=\"http://schemas.android.com/apk/res/android\" android:width='108dp' android:height='108dp' android:viewportWidth='108' android:viewportHeight='108'><path android:fillColor='#161826' android:pathData='M0,0h108v108h-108z'/><path android:fillColor='#9184D9' android:pathData='M30,40h48v30h-48z'/></vector>","path":"res/drawable/ic_v.xml"}""", expect = "vector drawable as given", images = true),
            Case("make_graphic", """{"svg":"<svg viewBox='0 0 10 10'><circle cx='5' r='4'></svg>","path":"assets/bad.svg"}""", expectError = true, expect = "line 1, column"),
            Case("web_fetch", """{"url":"https://example.com","max_chars":2000}""", expect = "Example Domain"),
            Case("web_fetch", """{"url":"http://example.com"}""", expectError = true),
            Case("check", "{}", expect = "BUILD OK"),
            Case("load_skill", """{"name":"timers"}""", expect = "LaunchedEffect"),
            Case("load_skill", """{"names":["timers","persistence","teleport"]}""", expect = "Not found: teleport"),
            Case("load_skill", """{"name":"teleport"}""", expectError = true, expect = "available"),
            Case("propose_rule", """{"rule":"Show volumes in ml, never oz","why":"user said so"}""", expect = "Proposed"),
            Case("security_check", "{}", expect = "security"),
            // A stale import (old package) and a missing one are fixed by Kiln itself — the model once
            // gave up on KeyboardOptions as "not in the kit" after importing it from the wrong package.
            Case("write_file", """{"path":"$src/Bad.kt","content":"package kiln.app.tooltest\n\nimport androidx.compose.ui.text.input.KeyboardOptions\n\nval k = KeyboardOptions(keyboardType = KeyboardType.Number)\n"}"""),
            Case("check", "{}", expect = "auto-fixed these"),
            Case("read_file", """{"path":"$src/Bad.kt"}""", expect = "import androidx.compose.foundation.text.KeyboardOptions"),
            // Ambiguous or unknown names are not guessed: the error stays, with hints.
            Case("write_file", """{"path":"$src/Bad.kt","content":"package kiln.app.tooltest\n\nval z = NoSuchThingAnywhere()\n"}"""),
            Case("check", "{}", expectError = true, expect = "NoSuchThingAnywhere"),
            // Compose functions and icons are top-level symbols, not classes: Icon must not become android.graphics.drawable.Icon.
            Case("write_file", """{"path":"$src/Bad.kt","content":"package kiln.app.tooltest\n\nimport androidx.compose.runtime.Composable\n\n@Composable\nfun Probe() {\n    Icon(Icons.Filled.Settings, \"Settings\")\n}\n"}"""),
            Case("check", "{}", expect = "auto-fixed these"),
            Case("read_file", """{"path":"$src/Bad.kt"}""", expect = "import androidx.compose.material.icons.filled.Settings"),
            Case("read_file", """{"path":"$src/Bad.kt"}""", expect = "import androidx.compose.material3.Icon\n"),
            // Lower-case Compose functions are imported too; a wrong kit parameter shows the real signature.
            Case("write_file", """{"path":"$src/Bad.kt","content":"package kiln.app.tooltest\n\nimport androidx.compose.runtime.Composable\nimport kotlinx.coroutines.flow.MutableStateFlow\n\n@Composable\nfun Probe2() {\n    val s = MutableStateFlow(1).collectAsStateWithLifecycle()\n    app.kiln.kit.KButton(\n        labelText = \"x\",\n    ) {}\n}\n"}"""),
            Case("check", "{}", expectError = true, expect = "KButton has no parameter 'labelText' — its signature is: fun KButton("),
            Case("read_file", """{"path":"$src/Bad.kt"}""", expect = "import androidx.lifecycle.compose.collectAsStateWithLifecycle"),
            // The project's own symbols: a wrong-package import is pointed at the real package, a missing one is added.
            Case("delete", """{"path":"$src/Bad.kt"}"""),
            Case("write_file", """{"path":"$src/data/Repo.kt","content":"package kiln.app.tooltest\n\nobject ExpenseRepo { val n = 1 }\n"}"""),
            Case("write_file", """{"path":"$src/data/Helper.kt","content":"package kiln.app.tooltest.data\n\nobject Helper { val m = 2 }\n"}"""),
            Case("write_file", """{"path":"$src/ui/Use.kt","content":"package kiln.app.tooltest.ui\n\nimport kiln.app.tooltest.data.ExpenseRepo\n\nval total = ExpenseRepo.n + Helper.m\n"}"""),
            Case("check", "{}", expect = "import kiln.app.tooltest.ExpenseRepo (was kiln.app.tooltest.data.ExpenseRepo)"),
            Case("read_file", """{"path":"$src/ui/Use.kt"}""", expect = "import kiln.app.tooltest.data.Helper"),
            Case("delete", """{"path":"$src/data"}"""),
            Case("delete", """{"path":"$src/ui"}"""),
            // An invented kit component is named as such, with what the kit has instead.
            Case("write_file", """{"path":"$src/Bad.kt","content":"package kiln.app.tooltest\n\nimport androidx.compose.runtime.Composable\n\n@Composable\nfun Bar() { KToolbar(title = \"x\") }\n"}"""),
            Case("check", "{}", expectError = true, expect = "KToolbar isn't in the kit. Closest:"),
            Case("check", "{}", expectError = true, expect = "KilnScreen"),
            // A capitalised package segment (run 9: Kiln.app…) is named as the root cause.
            Case("write_file", """{"path":"$src/Bad.kt","content":"package kiln.app.tooltest\n\nval broken: Kiln.app.tooltest.Nothing? = null\n"}"""),
            Case("check", "{}", expectError = true, expect = "Package names are lowercase"),
            // Run 16: a hand-written vector with fillColor="none" — Kiln makes it transparent and the build passes.
            Case("delete", """{"path":"$src/Bad.kt"}"""),
            Case("write_file", """{"path":"res/drawable/ic_none.xml","content":"<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" android:width=\"24dp\" android:height=\"24dp\" android:viewportWidth=\"24\" android:viewportHeight=\"24\"><path android:fillColor=\"none\" android:strokeColor=\"#FFFFFF\" android:strokeWidth=\"2\" android:pathData=\"M4,12h16\"/></vector>"}"""),
            Case("check", "{}", expect = "#00000000"),
            Case("delete", """{"path":"res/drawable/ic_none.xml"}"""),
            // Review: a kit parameter by its old name gets the new one.
            Case("write_file", """{"path":"$src/Bad.kt","content":"package kiln.app.tooltest\n\nimport androidx.compose.runtime.Composable\nimport app.kiln.kit.KTextField\n\n@Composable\nfun Old() { KTextField(\"\", onChange = { }) }\n"}"""),
            Case("check", "{}", expectError = true, expect = "onValueChange"),
            Case("load_skill", """{"names":"timers, persistence"}""", expect = "LaunchedEffect"),
            // Run 15: an icon name from another icon set.
            Case("write_file", """{"path":"$src/Bad.kt","content":"package kiln.app.tooltest\n\nimport androidx.compose.material.icons.Icons\nimport androidx.compose.material.icons.filled.Bell\n\nval bell = Icons.Filled.Bell\n"}"""),
            Case("check", "{}", expectError = true, expect = "use Icons.Filled.Notifications"),
            // Import mistakes from runs 2–9, all fixed by Kiln: a library class from the wrong package, a wrong
            // inline qualifier, and items(list) without its import (read as items(count: Int)). The package that isn't
            // bundled is fixed as soon as the file is written (the name check), the rest by the build.
            Case("write_file", """{"path":"$src/Bad.kt","content":"package kiln.app.tooltest\n\nimport androidx.compose.foundation.lazy.LazyColumn\nimport androidx.compose.material3.Text\nimport androidx.compose.runtime.Composable\nimport kotlinx.datetime.YearMonth\n\n@Composable\nfun Imports(names: List<String>) {\n    val month = YearMonth.now()\n    val kb = app.kiln.kit.KeyboardType.Number\n    LazyColumn { items(names) { Text(it.uppercase() + month + kb) } }\n}\n"}""",
                expect = "import kotlinx.datetime.YearMonth → java.time.YearMonth"),
            Case("check", "{}", expect = "BUILD OK"),
            Case("read_file", """{"path":"$src/Bad.kt"}""", expect = "import androidx.compose.foundation.lazy.items"),
            Case("grep", """{"pattern":"app.kiln.kit.KeyboardType","glob":"**/Bad.kt"}""", expect = "(no matches)"),
            Case("delete", """{"path":"$src/Bad.kt"}"""),
            // Kotlin outside src/ is refused before it's written.
            Case("write_file", """{"path":"ui/Stray.kt","content":"package x\n"}""", expectError = true, expect = "must be under src/"),
            // A misspelt project symbol gets a "did you mean" with the signature the model wrote.
            Case("write_file", """{"path":"$src/Bad.kt","content":"package kiln.app.tooltest\n\nfun HomeScreen() = 1\nfun probe() = HomeScren()\n"}"""),
            Case("check", "{}", expectError = true, expect = "fun HomeScreen()"),
            // Its own object's member, misremembered: what the object really has.
            Case("write_file", """{"path":"$src/Bad.kt","content":"package kiln.app.tooltest\n\nobject Repo { fun monthTotal(m: Int): Double = 0.0 }\nval t = Repo.currentMonthTotal()\n"}"""),
            Case("check", "{}", expectError = true, expect = "fun monthTotal(m: Int): Double"),
            // Names checked as written: a garbled package and a same-length typo of a kit name are fixed; real ones
            // (KCardBox and KDivider have mangled JVM names) are left alone.
            Case("write_file", """{"path":"$src/Bad.kt","content":"package kiln.app.tooltest\n\nimport androidx.compose.runtime.Composable\nimport app.kiln.kit.Koln.kit.rememberKToast\n\n@Composable\nfun Names() { val t = rememberKToast(); KolnScreen(\"x\") { KCardBox { KDivider() } } }\n"}""",
                expect = "KolnScreen → KilnScreen", absent = "KCardBox isn't"),
            Case("read_file", """{"path":"$src/Bad.kt"}""", expect = "import app.kiln.kit.rememberKToast"),
            // Told what to write from now on; the same slip in another file is fixed there too, and called a repeat.
            Case("write_file", """{"path":"$src/Bad2.kt","content":"package kiln.app.tooltest\n\nimport androidx.compose.runtime.Composable\n\n@Composable\nfun Names2() { KolnScreen(\"y\") { } }\n"}""",
                expect = "Again — KolnScreen"),
            Case("grep", """{"pattern":"KolnScreen","glob":"**/*.kt"}""", expect = "(no matches)"),
            Case("delete", """{"path":"$src/Bad2.kt"}"""),
            // A real class imported from the kit's package is pointed at its own; an icon that doesn't exist is named at once.
            Case("write_file", """{"path":"$src/Bad2.kt","content":"package kiln.app.tooltest\n\nimport androidx.compose.material.icons.Icons\nimport app.kiln.kit.Icons\nimport androidx.compose.material.icons.filled.Home\n\nval a = Icons.Filled.Home\nval b = Icons.Filled.Hoem\n"}""",
                expect = "Icons.Filled.Hoem isn't a Material icon. Closest: Icons.Filled.Home", absent = "Icons isn't in the kit"),
            Case("grep", """{"pattern":"import app.kiln.kit.Icons","glob":"**/Bad2.kt"}""", expect = "(no matches)"),
            Case("delete", """{"path":"$src/Bad2.kt"}"""),
            // An import of a name that doesn't exist and isn't used is dropped; a kit function called without its import gets it.
            Case("write_file", """{"path":"$src/Bad2.kt","content":"package kiln.app.tooltest\n\nimport androidx.compose.runtime.Composable\nimport app.kiln.kit.KText\nimport app.kiln.kit.Tone\n\n@Composable\nfun T() { val t = rememberKToast() }\n"}""",
                expect = "removed import app.kiln.kit.KText", absent = "KText isn't"),
            Case("read_file", """{"path":"$src/Bad2.kt"}""", expect = "import app.kiln.kit.rememberKToast"),
            Case("delete", """{"path":"$src/Bad2.kt"}"""),
            // A KCollection row's value read as the item: told to use row.value.
            Case("write_file", """{"path":"$src/Bad2.kt","content":"package kiln.app.tooltest\n\nimport app.kiln.kit.KCollection\nimport kotlinx.serialization.Serializable\n\n@Serializable data class Exp(val amount: Double = 0.0)\nval exps = KCollection<Exp>(\"exps\")\nval total = exps.rows.value.sumOf { it.amount }\n"}"""),
            Case("check", "{}", expectError = true, expect = "it.value.amount"),
            Case("delete", """{"path":"$src/Bad2.kt"}"""),
            // Review cases: the project's own names are left alone — a K-name close to a kit one (KidsTheme), a parameter
            // named like a library (loader), enum entries (KG), its own icon; its own class imported from the kit's package
            // is pointed at its package.
            Case("write_file", """{"path":"$src/Own.kt","content":"package kiln.app.tooltest\n\nimport androidx.compose.material.icons.Icons\nimport androidx.compose.ui.graphics.vector.ImageVector\n\nenum class WeightUnit { KG, LB }\nclass Expense(val amount: Double)\nval Icons.Filled.Logo: ImageVector get() = Icons.Filled.Home\n"}"""),
            Case("write_file", """{"path":"$src/Bad2.kt","content":"package kiln.app.tooltest\n\nimport androidx.compose.material.icons.Icons\nimport androidx.compose.runtime.Composable\nimport app.kiln.kit.Expense\n\n@Composable\nfun Section(loader: @Composable () -> Unit) { KidsTheme { loader() } }\nval u = WeightUnit.KG\nval logo = Icons.Filled.Logo\nval e = Expense(1.0)\n"}""",
                expect = "import app.kiln.kit.Expense → kiln.app.tooltest.Expense", absent = "KidsTheme → "),
            Case("grep", """{"pattern":"import app.kiln.kit.loader|KilnTheme","glob":"**/Bad2.kt"}""", expect = "(no matches)"),
            Case("delete", """{"path":"$src/Bad2.kt"}"""),
            Case("delete", """{"path":"$src/Own.kt"}"""),
            // Round 2: a wildcard import doesn't hide an invented K-name; an enum entry doesn't make a fake icon pass.
            Case("write_file", """{"path":"$src/Bad2.kt","content":"package kiln.app.tooltest\n\nimport androidx.compose.foundation.layout.*\nimport androidx.compose.material.icons.Icons\nimport androidx.compose.runtime.Composable\n\nenum class Tab { Home, Stats }\n@Composable\nfun W() { Column { KSlide() } }\nval i = Icons.Filled.Stats\n"}""",
                expect = "KSlide isn't in the kit"),
            Case("write_file", """{"path":"$src/Bad2.kt","content":"package kiln.app.tooltest\n\nimport androidx.compose.material.icons.Icons\n\nenum class Tab { Home, Stats }\nval i = Icons.Filled.Stats\n"}""",
                expect = "Icons.Filled.Stats isn't a Material icon"),
            // The build fixer still imports an icon whose name a project class shares (Screen.Settings), Icons.Default too.
            Case("delete", """{"path":"$src/Bad.kt"}"""),
            Case("write_file", """{"path":"$src/Bad2.kt","content":"package kiln.app.tooltest\n\nimport androidx.compose.material.icons.Icons\nimport androidx.compose.material3.Icon\nimport androidx.compose.runtime.Composable\n\nsealed class Screen { object Settings : Screen() }\n@Composable\nfun I() { Icon(Icons.Default.Settings, null) }\n"}"""),
            Case("check", "{}", expect = "BUILD OK"),
            Case("delete", """{"path":"$src/Bad2.kt"}"""),
            // Round 3: the icon, not the project's data class Settings, is imported for Icons.Filled.Settings.
            Case("write_file", """{"path":"$src/data/Settings.kt","content":"package kiln.app.tooltest.data\n\ndata class Settings(val budget: Double = 0.0)\n"}"""),
            Case("write_file", """{"path":"$src/Bad2.kt","content":"package kiln.app.tooltest\n\nimport androidx.compose.material.icons.Icons\nimport androidx.compose.material3.Icon\nimport androidx.compose.runtime.Composable\n\n@Composable\nfun S() { Icon(Icons.Filled.Settings, null) }\n"}"""),
            Case("check", "{}", expect = "BUILD OK"),
            Case("delete", """{"path":"$src/data"}"""),
            // A string inside a template isn't a name (KM); a correct Kiln… name of the project's isn't "garbled"; the line
            // reported is the line as saved, after the import Kiln added.
            Case("write_file", """{"path":"$src/Bad2.kt","content":"package kiln.app.tooltest\n\nimport androidx.compose.runtime.Composable\n\n@Composable\nfun W(dist: Int, metric: Boolean) { KCardBox { androidx.compose.material3.Text(\"${'$'}{dist} ${'$'}{if (metric) \"KM\" else \"MI\"}\") }; KilnTags(); KSlide() }\n"}""",
                expect = "line 7: KSlide", absent = "KM isn't"),
            Case("grep", """{"pattern":"KilnTabs","glob":"**/Bad2.kt"}""", expect = "(no matches)"),
            Case("delete", """{"path":"$src/Bad2.kt"}"""),
            // A member asked for by name gets the member's (Kotlin) signature from the kit catalog.
            Case("sdk_lookup", """{"query":"KFormat.money"}""", expect = "fun money(amount: Double"),
            // A Compose function in a differently named file is found by sdk_lookup (not android.app.DatePickerDialog).
            Case("sdk_lookup", """{"query":"DatePickerDialog"}""", expect = "androidx.compose.material3.DatePickerDialog — a top-level function"),
            // A name a letter off another component is suggested, never swapped in (KSlide meant KSwipeRow).
            Case("write_file", """{"path":"$src/Bad.kt","content":"package kiln.app.tooltest\n\nimport androidx.compose.runtime.Composable\n\n@Composable\nfun Slide() { KSlide() }\n"}""",
                expect = "KSlide isn't in the kit", absent = "KSlide → "),
            Case("delete", """{"path":"$src/Bad.kt"}"""),
            Case("clean", "{}"),
            Case("build", "{}", expect = "BUILD OK"),
        )
        val dev = if (!device) emptyList() else listOf(
            Case("install", "{}"),
            Case("run_app", """{"wait_ms":3000}""", expect = "running", images = true),
            Case("screenshot", "{}", expect = "screen pixels", images = true),
            Case("ui_check", "{}", expect = "checked"),
            Case("save_screenshot", """{"path":"store/screenshots/1-home.png"}""", expect = "saved store/screenshots/1-home.png"),
            Case("save_screenshot", """{"path":"src/x.png"}""", expectError = true, expect = "under store/"),
            Case("compare_screen", """{"target":"attachments/target.png"}""", expect = "Similarity", images = true),
            Case("compare_screen", """{"target":"kiln.json"}""", expectError = true, expect = "not an image"),
            Case("ui_tree", "{}", expect = "Built on this phone"),
            Case("tap", """{"target":"Text \"Built on this phone with Kiln.\"","x":0,"y":0}""", expect = "No change on screen"),
            Case("tap", """{"target":"640, 1400"}""", expect = "640,1400"),
            Case("tap", """{"target":"the purple rocket button"}""", expectError = true, expect = "On screen now"),
            Case("wait_for", """{"target":"Tool Test","timeout_ms":3000}""", expect = "on screen"),
            Case("type_text", """{"text":"hello world"}""", expectError = true, expect = "no text field has focus"),
            // A whole journey in one call; a failing expectation names what is on screen instead.
            Case("test_flow", """{"steps":[{"expect":"Built on this phone"},{"tap":"Built on this phone with Kiln."},{"expect_gone":"Nothing like this","timeout_ms":500},{"wait_ms":100}]}""", expect = "all 4 steps passed", images = true),
            Case("test_flow", """{"steps":[{"expect":"No such text anywhere","timeout_ms":500},{"tap":"x"}]}""", expectError = true, expect = "stopped at step 1"),
            // Steps written the way run 7's model wrote them: tool names as keys, objects instead of strings.
            Case("test_flow", """{"steps":[{"wait_for":{"text":"Built on this phone"}},{"tap":{"text":"Built on this phone with Kiln."}},{"wait":{"ms":"100"}}]}""", expect = "all 3 steps passed"),
            Case("swipe", """{"direction":"up"}"""),
            Case("swipe", """{"direction":"up","target":"[632,1676]"}""", expect = "swiped up"),   // a point, as for tap (run 10 QA)
            Case("press_key", """{"key":"BACK"}"""),
            Case("launch", "{}"),
            Case("logcat", """{"since_last":false,"level":"error","grep":""}"""),
            Case("logcat", """{"since_last":true,"level":"I"}"""),
            Case("logcat", """{"level":"loud"}""", expectError = true),
            Case("last_crash", "{}"),
            Case("dumpsys", """{"service":"meminfo"}""", expect = "kiln.app.tooltest"),
            Case("grant_permission", """{"permission":"POST_NOTIFICATIONS"}"""),
            Case("shell", """{"command":"echo kiln-ok"}""", expect = "kiln-ok"),
            Case("stop_app", "{}"),
            Case("tap", """{"target":"Stop"}""", expectError = true, expect = "not in front"),  // never taps other apps
            Case("clear_data", "{}"),
        )

        val failures = mutableListOf<String>()
        for (c in files + dev) {
            val tool = tools[c.tool]
            if (tool == null) { failures += "${c.tool}: not registered"; continue }
            val input = parseJson(c.input) as JsonObject
            val invalid = validateInput(tool, input)
            val r: ToolResult = if (invalid != null) ToolResult.error("invalid input: $invalid")
                else runCatching { tool.run(ctx, input) }.getOrElse { ToolResult.error("${c.tool} failed: ${it.message}") }  // as AgentLoop.runOne
            val text = r.text
            val ok = r.isError == c.expectError &&
                (c.expect == null || text.contains(c.expect, ignoreCase = true)) &&
                (!c.images || r.images.isNotEmpty()) &&
                (c.absent == null || !text.contains(c.absent, ignoreCase = true))
            println("TOOLTEST ${if (ok) "PASS" else "FAIL"} ${c.tool.padEnd(16)} ${c.input.take(70)} -> ${if (r.isError) "ERR " else ""}${text.replace('\n', ' ').take(if (ok) 160 else 1500)}")
            if (!ok) failures += "${c.tool} ${c.input.take(60)} -> ${if (r.isError) "error" else "ok"}: ${text.take(200)}"
        }
        // Every registered tool appears at least once above (subagent and qa_check need a model; MCP/command tools are user-defined).
        val covered = (files + dev).map { it.tool }.toSet()
        val skipped = tools.keys - covered - setOf("subagent", "qa_check", "read_output") - tools.keys.filter { '.' in it || it.startsWith("mcp_") }.toSet()
        println("TOOLTEST tools=${tools.size} covered=${covered.size} device=$device uncovered=$skipped")
        // Leave nothing behind: the project, its sessions and the installed app.
        if (device) Graph.device.uninstall(p.meta().`package`)
        p.dir.deleteRecursively()
        if (device) assertTrue("not exercised: $skipped", skipped.isEmpty())
        assertTrue("${failures.size} tool calls misbehaved:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
