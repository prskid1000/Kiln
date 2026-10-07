package app.kiln

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
                            val images: Boolean = false)

    @Test fun everyToolWorks() = runBlocking {
        Graph.init(app)
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
            Case("ask_user", """{"question":"Which colour?","options":["Accent","Ok"]}"""),
            Case("sdk_lookup", """{"query":"class KStore"}""", expect = "app.kiln.kit.KStore"),
            Case("sdk_lookup", """{"query":"KStore","member":"*"}""", expect = "getState"),
            Case("sdk_lookup", """{"query":"KStore","member":"KStore"}""", expect = "constructor"),
            Case("sdk_lookup", """{"query":"app.kiln.kit KStore","member":"update"}""", expect = "update"),
            Case("sdk_lookup", """{"query":"KilnActivity onCreate"}""", expect = "fun onCreate"),
            Case("sdk_lookup", """{"query":"KilnActivity","member":"setContent"}""", expect = "inherited from"),
            Case("sdk_lookup", """{"query":"Icons.Filled.Home"}""", expect = "Home"),
            Case("sdk_lookup", """{"query":"CameraCharacteristics","member":"FLASHLIGHT"}""", expect = "similar"),
            Case("sdk_lookup", """{"query":"Modifier.padding"}""", expect = "padding"),
            Case("sdk_lookup", """{"query":"Zzqqxx"}""", expectError = true),
            Case("kit_docs", """{"query":"App icon"}""", expect = "ic_launcher"),
            Case("kit_docs", """{"query":"KStore"}""", expect = "KStore"),
            Case("web_fetch", """{"url":"https://example.com","max_chars":2000}""", expect = "Example Domain"),
            Case("web_fetch", """{"url":"http://example.com"}""", expectError = true),
            Case("check", "{}", expect = "BUILD OK"),
            Case("clean", "{}"),
            Case("build", "{}", expect = "BUILD OK"),
        )
        val dev = if (!device) emptyList() else listOf(
            Case("install", "{}"),
            Case("run_app", """{"wait_ms":3000}""", expect = "running", images = true),
            Case("screenshot", "{}", expect = "screen pixels", images = true),
            Case("ui_tree", "{}", expect = "Built on this phone"),
            Case("tap", """{"target":"Text \"Built on this phone with Kiln.\"","x":0,"y":0}""", expect = "tapped"),
            Case("tap", """{"target":"640, 1400"}""", expect = "640,1400"),
            Case("tap", """{"target":"the purple rocket button"}""", expectError = true, expect = "On screen now"),
            Case("wait_for", """{"target":"Tool Test","timeout_ms":3000}""", expect = "on screen"),
            Case("type_text", """{"text":"hello world"}"""),
            Case("swipe", """{"direction":"up"}"""),
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
                (!c.images || r.images.isNotEmpty()) 
            println("TOOLTEST ${if (ok) "PASS" else "FAIL"} ${c.tool.padEnd(16)} ${c.input.take(70)} -> ${if (r.isError) "ERR " else ""}${text.replace('\n', ' ').take(160)}")
            if (!ok) failures += "${c.tool} ${c.input.take(60)} -> ${if (r.isError) "error" else "ok"}: ${text.take(200)}"
        }
        // Every registered tool appears at least once above (subagent needs a model; MCP/command tools are user-defined).
        val covered = (files + dev).map { it.tool }.toSet()
        val skipped = tools.keys - covered - setOf("subagent", "read_output") - tools.keys.filter { '.' in it || it.startsWith("mcp_") }.toSet()
        println("TOOLTEST tools=${tools.size} covered=${covered.size} device=$device uncovered=$skipped")
        if (device) assertTrue("not exercised: $skipped", skipped.isEmpty())
        assertTrue("${failures.size} tool calls misbehaved:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
