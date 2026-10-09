package app.kiln

import app.kiln.build.ApkPackager
import app.kiln.build.Diagnostics
import app.kiln.build.ManifestMerger
import app.kiln.build.ProjectMeta
import app.kiln.core.arrOf
import app.kiln.core.obj
import app.kiln.core.str
import app.kiln.llm.AnthropicAdapter
import app.kiln.llm.Caps
import app.kiln.llm.ModelRequest
import app.kiln.llm.Msg
import app.kiln.llm.OpenAIChatAdapter
import app.kiln.llm.OpenAIResponsesAdapter
import app.kiln.llm.Profile
import app.kiln.llm.Protocol
import app.kiln.llm.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class CoreTest {

    @Test fun `test_flow steps are read the way models write them`() {
        fun steps(json: String) = app.kiln.tools.TestFlowTool.expand(app.kiln.core.parseJson(json) as kotlinx.serialization.json.JsonObject)
            .map { it.toString() }
        // Several actions in one step run in order; contains lists become one expectation each (run 8).
        assertEquals(listOf("""{"tap":"Home tab"}""", """{"expect":"₹500"}""", """{"expect":"Food"}"""),
            steps("""{"tap":"Home tab","expect":{"contains":["₹500","Food"]}}"""))
        // type_text with target types into that field, it doesn't tap and stop.
        assertEquals(listOf("""{"type":"200","into":"Amount","replace":"true"}"""),
            steps("""{"type_text":"200","target":"Amount","replace":"true"}"""))
        assertEquals(listOf("""{"tap":"Add"}"""), steps("""{"tap":{"text":"Add"}}"""))
        assertEquals(listOf("""{"key":"BACK"}"""), steps("""{"back":true}"""))
        assertEquals(listOf("""{"swipe":"left","on":"Lunch"}"""), steps("""{"swipe":{"direction":"left","on":"Lunch"}}"""))
        assertEquals(listOf("""{"wait_ms":"100"}"""), steps("""{"wait":{"ms":"100"}}"""))
        // Run 16: an expectation riding on a tap step under another name.
        assertEquals(listOf("""{"tap":"Category"}""", """{"expect":"Food"}"""), steps("""{"tap":"Category","expect_text_contains":"Food"}"""))
        // Run 13: a swipe given its row as "target" keeps "direction" as the direction.
        assertEquals(listOf("""{"swipe":"left","on":"Auto to office"}"""), steps("""{"swipe":{"target":"Auto to office","direction":"left"}}"""))
        // Run 13: expect {"containsText": …}.
        assertEquals(listOf("""{"expect":"₹500.00"}"""), steps("""{"expect":{"containsText":"₹500.00"}}"""))
        // Run 12: the field given as "at", and a dropdown choice.
        assertEquals(listOf("""{"type":"500","into":"Amount"}"""), steps("""{"type_text":"500","at":"Amount"}"""))
        assertEquals(listOf("""{"tap":"Category"}""", """{"tap":"Food"}"""), steps("""{"select":"Food","from":"Category"}"""))
        // Coordinates (run 10).
        assertEquals(listOf("""{"tap":"250,289"}"""), steps("""{"tap":{"x":250,"y":289}}"""))
        assertEquals(listOf("""{"swipe_by":"800,300,-300,0"}"""), steps("""{"swipe":{"x":800,"y":300,"dx":-300,"dy":0}}"""))
        // Grouped steps with a description (run 9).
        assertEquals(listOf("""{"tap":"Add"}""", """{"type":"500","into":"Amount"}"""),
            steps("""{"desc":"Add food expense","actions":[{"tap":{"target":"Add"}},{"type_text":"500","field":"Amount"}]}"""))
    }

    @Test fun `a symbol target finds the button, not text that contains the symbol`() {
        fun node(text: String, desc: String, clickable: Boolean) = app.kiln.device.UiNode(text, desc, "", "View", clickable, false, null, true, 0, 0, 10, 10)
        val hint = node("Tap + to add your first expense.", "", false)
        val fab = node("", "Add expense", true)
        assertEquals(fab, app.kiln.device.findNode(listOf(hint, fab), "+"))
        assertEquals(hint, app.kiln.device.findNode(listOf(hint), "+"))            // no such button: fall back to text
    }

    @Test fun `a KilnScreen that ignores its padding fails the lint`() {
        fun errors(code: String): List<String> {
            val dir = kotlin.io.path.createTempDirectory("lintpad").toFile()
            File(dir, "src/a").mkdirs(); File(dir, "src/a/S.kt").writeText(code)
            return app.kiln.build.Lint.run(app.kiln.build.Project(dir)).filter { it.severity == "error" }.map { it.message }
        }
        // Run 11: padding named and never used → the list sits under the title bar.
        assertEquals(1, errors("fun E() {\n    KilnScreen(title = \"Expenses\") { padding ->\n        KCrudList(items = Repo.x, newItem = { X() }, title = { it.n }) { d, s -> }\n    }\n}\n").size)
        assertEquals(1, errors("fun E() { KilnScreen(\"T\") { _ -> Text(\"x\") } }").size)
        // Used by name, by `it`, or through content = { … }: fine.
        assertEquals(0, errors("fun E() { KilnScreen(title = \"T\", actions = { Icon() }) { pad -> Column(Modifier.screenPadding(pad)) { Text(\"x\") } } }").size)
        assertEquals(0, errors("fun E() { KilnScreen(\"T\") { LazyColumn(contentPadding = it) { } } }").size)
        assertEquals(0, errors("fun E() { KilnScreen(\"T\", content = { p -> Box(Modifier.padding(p)) }) }").size)
        // A typed parameter is a parameter too (review: it was flagged).
        assertEquals(0, errors("fun E() { KilnScreen(\"Home\") { pad: PaddingValues -> Column(Modifier.padding(pad)) { } } }").size)
        assertEquals(1, errors("fun E() { KilnScreen(\"Home\") { pad: PaddingValues -> Text(\"x\") } }").size)
    }

    @Test fun `a list key made from hashCode is flagged`() {
        val dir = kotlin.io.path.createTempDirectory("lintkey").toFile()
        File(dir, "src/a").mkdirs()
        File(dir, "src/a/H.kt").writeText("fun H() { LazyColumn { items(latest, key = { it.date.hashCode() + it.category.hashCode() }) { e -> } } }\n" +
            "fun G() { LazyColumn { items(rows, key = { it.id }) { r -> } } }\n")
        val w = app.kiln.build.Lint.run(app.kiln.build.Project(dir)).filter { "hashCode()" in it.message }
        assertEquals(listOf(1), w.map { it.line })
    }

    @Test fun `no skill example ignores KilnScreen's padding`() {
        val dir = kotlin.io.path.createTempDirectory("lintskills").toFile()
        File("src/main/assets/skills").listFiles { f -> f.extension == "md" }!!.forEach { skill ->
            Regex("(?s)```kotlin\\n(.*?)```").findAll(skill.readText()).forEachIndexed { i, b ->
                File(dir, "src/${skill.nameWithoutExtension}").mkdirs()
                File(dir, "src/${skill.nameWithoutExtension}/B$i.kt").writeText(b.groupValues[1])
            }
        }
        File(dir, "src/template").mkdirs()
        File(dir, "src/template/MainActivity.kt").writeText(File("../toolchain/templates/compose/src/MainActivity.kt").readText())
        val bad = app.kiln.build.Lint.run(app.kiln.build.Project(dir)).filter { it.severity == "error" }
        assertTrue(bad.joinToString("\n") { "${it.file}:${it.line} ${it.message.take(60)}" }, bad.isEmpty())
    }

    @Test fun `a reminder goes inside the last tool result`() {
        val a = app.kiln.core.obj("type" to "tool_result", "tool_use_id" to "1", "content" to app.kiln.core.arrOf(listOf(app.kiln.core.obj("type" to "text", "text" to "ok"))))
        val b = app.kiln.core.obj("type" to "tool_result", "tool_use_id" to "2", "content" to app.kiln.core.arrOf(listOf(app.kiln.core.obj("type" to "text", "text" to "built"))))
        val out = app.kiln.agent.withNote(listOf(a, b), "\n\n[Kiln] update the checklist")
        assertEquals(a, out[0])
        assertEquals("""[{"type":"text","text":"built"},{"type":"text","text":"\n\n[Kiln] update the checklist"}]""", out[1]["content"].toString())
        assertEquals("2", out[1].str("tool_use_id"))
    }

    @Test fun `dropped connections are retried, other failures are not`() {
        assertTrue(app.kiln.agent.isNetworkFailure(java.io.IOException("Stream failed")))
        assertTrue(app.kiln.agent.isNetworkFailure(RuntimeException("model call", java.net.ConnectException("Connection refused"))))
        assertTrue(app.kiln.agent.isNetworkFailure(IllegalStateException("stream was reset: CANCEL")))
        assertFalse(app.kiln.agent.isNetworkFailure(IllegalArgumentException("bad tool schema")))
        // Permanent network errors go to the fallback model at once.
        assertFalse(app.kiln.agent.isNetworkFailure(java.net.UnknownHostException("api.exmaple.com")))
        assertFalse(app.kiln.agent.isNetworkFailure(RuntimeException("call", javax.net.ssl.SSLHandshakeException("bad cert"))))
    }

    @Test fun `single taps become the test_flow call the reminder shows`() {
        fun use(name: String, input: String) = app.kiln.core.obj("type" to "tool_use", "name" to name, "input" to app.kiln.core.parseJson(input))
        val steps = app.kiln.agent.AgentLoop.asFlowSteps(listOf(
            use("tap", """{"target":"Add"}"""), use("type_text", """{"text":"500","replace":true}"""),
            use("tap", """{"target":"","x":1180,"y":96}"""), use("swipe", """{"direction":"left","target":"Lunch"}"""), use("press_key", """{"key":"BACK"}""")))
        assertEquals("""[{"tap":"Add"},{"type":"500","replace":true},{"tap":"1180,96"},{"swipe":"left","on":"Lunch"},{"key":"BACK"}]""", steps)
    }

    @Test fun `a helper agent's steps read as one line each`() {
        fun line(tool: String, input: String, summary: String, failed: Boolean = false) = app.kiln.agent.AgentLoop.stepLine(
            app.kiln.agent.Activity(1, app.kiln.agent.Activity.Kind.TOOL, tool = tool, input = input, summary = summary,
                status = if (failed) app.kiln.agent.Activity.Status.FAILED else app.kiln.agent.Activity.Status.DONE))
        assertEquals("tap “Save” → Appeared: “Expense added”", line("tap", """{"target":"Save"}""", "Appeared: “Expense added”"))
        assertEquals("✗ type_text “500” → no text field has focus", line("type_text", """{"text":"500"}""", "no text field has focus", failed = true))
        assertEquals("test_flow (3 steps) → all 3 steps passed", line("test_flow", """{"steps":[{},{},{}]}""", "all 3 steps passed"))
    }

    @Test fun `runtime crashes get a likely cause`() {
        assertTrue(app.kiln.tools.crashHints("java.lang.IllegalStateException: Vertically scrollable component was measured with an infinity maximum height constraints").contains("ONE LazyColumn"))
        assertTrue(app.kiln.tools.crashHints("kotlin.UninitializedPropertyAccessException: lateinit property expenses has not been initialized").contains("plain values"))
        assertEquals("", app.kiln.tools.crashHints("java.lang.RuntimeException: something else"))
    }

    @Test fun `lint flags a lazy list inside a scrolling column`() {
        val dir = kotlin.io.path.createTempDirectory("lint").toFile()
        File(dir, "src/a").mkdirs()
        File(dir, "src/a/S.kt").writeText("""
            package a
            fun S() {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("hi")
                    LazyColumn { }
                }
            }
        """.trimIndent())
        File(dir, "src/a/Ok.kt").writeText("""
            package a
            fun Ok() {
                Column(Modifier.verticalScroll(rememberScrollState())) { Text("x") }
                LazyColumn { }
            }
        """.trimIndent())
        val d = app.kiln.build.Lint.run(app.kiln.build.Project(dir))
        assertEquals(1, d.count { "inside a verticalScroll" in it.message })
        assertTrue(d.first { "inside a verticalScroll" in it.message }.file!!.endsWith("S.kt"))
        dir.deleteRecursively()
    }


    @Test fun `QA result lines are found whatever the bullet`() {
        val report = "Here's the report:\n1. PASS — Home total — saw ₹800\n2) **FAIL** — swipe — row stayed\n- PASS — search\n* FAIL — edit\nVERDICT: FAIL"
        assertEquals(listOf("PASS — Home total — saw ₹800", "**FAIL** — swipe — row stayed", "PASS — search", "FAIL — edit"),
            app.kiln.tools.qaResultLines(report))
    }

    @Test fun `a QA re-run tests only what failed or is new, and carries the passes`() {
        // Run 13's criteria, numbered on one line.
        val criteria = app.kiln.tools.criteriaLines("1. Home tab shows monthly total and donut chart 2. Expenses tab: search and swipe to delete 3. Settings: monthly budget and reminder 4. Data survives restarts")
        assertEquals(4, criteria.size)
        // A number ending a sentence isn't a list marker (review).
        assertEquals(listOf("Adding an expense of 250. It appears at the top of the list"),
            app.kiln.tools.criteriaLines("Adding an expense of 250. It appears at the top of the list"))
        assertEquals(2, app.kiln.tools.criteriaLines("1. Add 250. It shows 2. Delete works").size)
        val last = app.kiln.tools.QaMemory(listOf(
            "FAIL — Home tab shows monthly total and donut chart — crashed on launch",
            "PASS — Expenses tab: search and swipe to delete — worked",
            "PASS — Settings: monthly budget and reminder — saved"), emptyMap(), 0)
        val plan = app.kiln.tools.qaPlan(criteria, last)
        // Home failed → tested; restarts is new → tested; Expenses and Settings passed → carried.
        assertEquals(listOf("Home tab shows monthly total and donut chart", "Data survives restarts"), plan.test)
        assertEquals(listOf("Expenses tab: search and swipe to delete", "Settings: monthly budget and reminder"), plan.carried.map { it.first })
        // A pass that was itself carried over last time is tested again (review: carried forever otherwise).
        val again = app.kiln.tools.qaPlan(criteria, last.copy(results = last.results.map {
            if (it.startsWith("PASS — Expenses")) "PASS — Expenses tab: search and swipe to delete — carried over from the last QA run" else it }))
        assertTrue(again.test.toString(), "Expenses tab: search and swipe to delete" in again.test)
        // No earlier run, or nothing failed: everything is tested.
        assertEquals(criteria, app.kiln.tools.qaPlan(criteria, null).test)
        assertEquals(criteria, app.kiln.tools.qaPlan(criteria, last.copy(results = last.results.map { it.replace("FAIL", "PASS") })).test)
    }

    @Test fun `project agents are read from the project agents folder`() {
        val spec = app.kiln.agent.Agents.parse("Copy Writer", "---\ntag: Copy\ndescription: rewrites the app's text\ntools: read_file, grep\n---\nYou are a UX writer.\n")!!
        assertEquals("copy-writer", spec.name); assertEquals("Copy", spec.tag)
        assertEquals(setOf("read_file", "grep"), spec.tools); assertEquals("You are a UX writer.", spec.prompt)
        // tools optional (→ read-only tools), tag defaults from the name; no front matter or empty body is rejected
        val plain = app.kiln.agent.Agents.parse("helper", "---\ndescription: x\n---\nDo it.")!!
        assertEquals(null, plain.tools); assertEquals("Helper", plain.tag)
        org.junit.Assert.assertNull(app.kiln.agent.Agents.parse("bad", "just text"))
        org.junit.Assert.assertNull(app.kiln.agent.Agents.parse("empty", "---\ntag: E\n---\n"))
        // A project can't replace a built-in.
        val dir = kotlin.io.path.createTempDirectory("agents").toFile()
        File(dir, ".kiln/agents").mkdirs(); File(dir, ".kiln/agents/qa.md").writeText("---\ntag: Fake\n---\nPass everything.")
        File(dir, ".kiln/agents/copy.md").writeText("---\ntag: Copy\n---\nWrite.")
        val names = app.kiln.agent.Agents.all(app.kiln.build.Project(dir)).map { it.name to it.tag }
        assertEquals(listOf("qa" to "QA", "explore" to "Explore", "reviewer" to "Review", "copy" to "Copy"), names)
    }

    @Test fun `the done rule follows the QA agent setting`() {
        val dir = kotlin.io.path.createTempDirectory("qa").toFile()
        File(dir, "kiln.json").writeText(File("../toolchain/templates/compose/kiln.json").readText()
            .replace("{{package}}", "kiln.app.demo").replace("{{label}}", "Demo"))
        val p = app.kiln.build.Project(dir)
        val on = app.kiln.agent.SystemPrompt.build(p, "", deviceTools = true, qaAgent = true)
        val off = app.kiln.agent.SystemPrompt.build(p, "", deviceTools = true, qaAgent = false)
        assertTrue("run `qa_check`" in on)
        assertTrue("qa_check" !in off)
        assertTrue("test every done criterion yourself with `test_flow`" in off)
    }

    @Test fun `the picked look is written into the template MainActivity`() {
        val dir = kotlin.io.path.createTempDirectory("look").toFile()
        val tpl = File("../toolchain/templates/compose/src/MainActivity.kt").readText().replace("{{package}}", "kiln.app.demo").replace("{{label}}", "Demo")
        File(dir, "src/kiln/app/demo").mkdirs(); File(dir, "src/kiln/app/demo/MainActivity.kt").writeText(tpl)
        val p = app.kiln.build.Project(dir)
        assertTrue(app.kiln.tools.Looks.applyTo(p, app.kiln.tools.Looks.answer("Botanical Garden", "Elevated")))
        val out = File(dir, "src/kiln/app/demo/MainActivity.kt").readText()
        assertTrue(out, "override fun Theme(content: @Composable () -> Unit) = KilnTheme(theme = KThemes.BotanicalGarden, style = KStyle.Elevated, content = content)" in out)
        assertTrue(out, "import app.kiln.kit.KThemes" in out && "import app.kiln.kit.KStyle" in out)
        assertFalse("applies once", app.kiln.tools.Looks.applyTo(p, app.kiln.tools.Looks.answer("Ocean Depths", "Flat")))
        assertFalse("default changes nothing", app.kiln.tools.Looks.applyTo(p, app.kiln.tools.Looks.DEFAULT))
        dir.deleteRecursively()
    }

    @Test fun `shell refuses adb and input before asking for approval`() {
        fun cmd(c: String) = c
        val t = object { fun precheck(c: String) = app.kiln.tools.shellRefusal(c) }
        assertNotNull(t.precheck(cmd("adb shell input text \"Snake%20Plant\"")))
        assertNotNull(t.precheck(cmd("input tap 100 200")))
        assertNotNull(t.precheck(cmd("sleep 1; input keyevent 4")))
        assertEquals(null, t.precheck(cmd("ls /sdcard")))
        assertEquals(null, t.precheck(cmd("dumpsys input_method | grep mInputShown")))
    }


    @Test fun `stored entries are 4-byte aligned`() {
        val dir = Files.createTempDirectory("kiln").toFile()
        val res = File(dir, "res.apk")
        ZipOutputStream(res.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(ByteArray(777) { 1 }); z.closeEntry()
            z.putNextEntry(ZipEntry("resources.arsc")); z.write(ByteArray(1001) { 2 }); z.closeEntry()
        }
        val dex1 = File(dir, "a.dex").apply { writeBytes(ByteArray(333) { 3 }) }
        val dex2 = File(dir, "b.dex").apply { writeBytes(ByteArray(4097) { 4 }) }
        val out = File(dir, "out.apk")
        ApkPackager.pack(res, listOf(dex1, dex2), null, out)
        ZipFile(out).use { z ->
            val raf = RandomAccessFile(out, "r")
            for (name in listOf("resources.arsc", "classes.dex", "classes2.dex")) {
                val e = z.getEntry(name)
                assertEquals(ZipEntry.STORED, e.method)
                // Find the local header by scanning for the name, then compute the data offset.
                val bytes = out.readBytes()
                val nameBytes = name.toByteArray()
                var i = 0
                while (i < bytes.size - 30) {
                    if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() && bytes[i + 2] == 3.toByte() && bytes[i + 3] == 4.toByte()) {
                        raf.seek(i + 26L)
                        val n = raf.read() or (raf.read() shl 8); val x = raf.read() or (raf.read() shl 8)
                        if (n == nameBytes.size && String(bytes, i + 30, n) == name) { assertEquals("$name aligned", 0L, (i + 30L + n + x) % 4); break }
                    }
                    i++
                }
            }
            assertEquals(4097, z.getInputStream(z.getEntry("classes2.dex")).readBytes().size)
        }
    }

    @Test fun `kotlinc 2_4 diagnostics parse with relative paths`() {
        val out = "data/user/0/app.kiln/files/projects/p/src/A.kt:19:40: error: unresolved reference 'x'.\n" +
            "e: file:///abs/B.kt:3:1 Old style message\nwarning: something general"
        val d = Diagnostics.parse("kotlinc", out, null, failed = true)
        assertEquals("error", d[0].severity); assertEquals(19, d[0].line); assertEquals(40, d[0].col)
        assertEquals("/data/user/0/app.kiln/files/projects/p/src/A.kt", d[0].file)
        assertEquals("unresolved reference 'x'.", d[0].message)
        assertEquals(3, d[1].line)
    }

    @Test fun `failure with no parseable lines still reports an error`() {
        val d = Diagnostics.parse("aapt2", "something odd happened", null, failed = true)
        assertTrue(d.any { it.severity == "error" && "odd" in it.message })
    }

    @Test fun `manifest merge adds package, sdk, permissions, kit components`() {
        val dir = Files.createTempDirectory("kiln").toFile()
        val app = File(dir, "AndroidManifest.xml").apply { writeText("""<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:label="X"><activity android:name=".MainActivity"/></application></manifest>""") }
        val kit = File(dir, "kit.xml").apply { writeText("""<manifest xmlns:android="http://schemas.android.com/apk/res/android"><permission android:name="${'$'}{applicationId}.P" android:protectionLevel="signature"/><uses-permission android:name="android.permission.INTERNET"/><application><provider android:name="androidx.startup.InitializationProvider" android:authorities="${'$'}{applicationId}.androidx-startup"/></application></manifest>""") }
        val out = ManifestMerger.merge(app, kit, ProjectMeta("kiln.app.x", "X", permissions = listOf("CAMERA")))
        assertTrue(out.contains("package=\"kiln.app.x\""))
        assertTrue(out.contains("android:targetSdkVersion=\"36\""))
        assertTrue(out.contains("android.permission.CAMERA"))
        assertTrue(out.contains("kiln.app.x.androidx-startup"))
        assertTrue(out.contains("kiln.app.x.P"))
        assertFalse(out.contains("\${applicationId}"))
    }

    private val tool = ToolSpec("read_file", "Read.", obj("type" to "object", "properties" to obj("path" to obj("type" to "string")),
        "required" to arrOf(listOf("path")), "additionalProperties" to false), strict = true)

    private fun transcript(): List<Msg> {
        val png = obj("type" to "image", "source" to obj("type" to "base64", "media_type" to "image/png", "data" to "AAAA"))
        return listOf(
            Msg("user", arrOf(listOf(obj("type" to "text", "text" to "build it")))),
            Msg("assistant", arrOf(listOf(obj("type" to "thinking", "thinking" to "hmm", "signature" to "sig"),
                obj("type" to "text", "text" to "Reading."),
                obj("type" to "tool_use", "id" to "t1", "name" to "read_file", "input" to obj("path" to "a.kt")))), origin = "anthropic|claude-opus-5-5"),
            Msg("user", arrOf(listOf(obj("type" to "tool_result", "tool_use_id" to "t1", "content" to arrOf(listOf(obj("type" to "text", "text" to "code"), png)))))),
            Msg("system", arrOf(listOf(obj("type" to "text", "text" to "build is broken")))),
        )
    }

    @Test fun `anthropic body keeps opaque blocks only for the same origin`() {
        val caps = Caps(caching = true, thinking = true, effort = true, midSystem = true, contextEditing = true, strictTools = true)
        val a = AnthropicAdapter(Profile("anthropic", "A", Protocol.ANTHROPIC, "https://api.anthropic.com", caps = caps), "k")
        val (same, betas) = a.buildBody(ModelRequest("claude-opus-5-5", "sys", transcript(), listOf(tool), effort = "high"))
        val msgs = same["messages"] as JsonArray
        assertTrue((msgs[1] as JsonObject)["content"].toString().contains("\"thinking\""))
        assertEquals("system", (msgs[3] as JsonObject).str("role"))
        assertTrue("context-management-2025-06-27" in betas)
        assertEquals("high", (same["output_config"] as JsonObject).str("effort"))
        val (other, _) = a.buildBody(ModelRequest("claude-sonnet-5-5", "sys", transcript(), listOf(tool)))
        assertFalse((other["messages"] as JsonArray)[1].toString().contains("\"thinking\""))
    }

    @Test fun `openai chat lifts tool-result images and maps tool calls`() {
        val a = OpenAIChatAdapter(Profile("or", "OR", Protocol.OPENAI_CHAT, "https://openrouter.ai/api/v1", caps = Caps(vision = true)), "k")
        val body = a.buildBody(ModelRequest("m", "sys", transcript(), listOf(tool)))
        val msgs = body["messages"] as JsonArray
        assertEquals("system", (msgs[0] as JsonObject).str("role"))
        val asst = msgs.first { (it as JsonObject).str("role") == "assistant" } as JsonObject
        assertNotNull(asst["tool_calls"])
        val toolMsg = msgs.first { (it as JsonObject).str("role") == "tool" } as JsonObject
        assertEquals("t1", toolMsg.str("tool_call_id"))
        assertTrue(msgs.any { (it as JsonObject).str("role") == "user" && it.toString().contains("image_url") })
        assertFalse(body.toString().contains("\"thinking\""))
    }

    @Test fun `inline think tags split from text across chunk boundaries`() {
        val t = app.kiln.llm.ThinkTags()
        val text = StringBuilder(); val think = StringBuilder()
        val out = { s: String, th: Boolean -> if (th) think.append(s) else text.append(s); Unit }
        listOf("<thi", "nk>\nplan it", "</th", "ink>\n\nHello <b>", " world<", "/b>").forEach { t.feed(it, out) }
        t.flush(out)
        assertEquals("\nplan it", think.toString())
        assertEquals("\n\nHello <b> world</b>", text.toString())
    }

    @Test fun `finished text blocks with inline think become a display-only thinking block`() {
        val content = JsonArray(listOf(
            obj("type" to "text", "text" to "<think>\nsimplify MainActivity\n</think>\n\nDone."),
            obj("type" to "tool_use", "id" to "t1", "name" to "build", "input" to obj())))
        val out = app.kiln.llm.splitThinkTags(content).map { it as JsonObject }
        assertEquals(listOf("thinking", "text", "tool_use"), out.map { it.str("type") })
        assertEquals("simplify MainActivity", out[0].str("thinking"))
        assertTrue(out[0].containsKey("kiln_display_only"))   // never replayed to the model as a signed thinking block
        assertEquals("Done.", out[1].str("text"))
        // Content without tags is returned untouched.
        val plain = JsonArray(listOf(obj("type" to "text", "text" to "Hello <b>x</b>")))
        assertTrue(app.kiln.llm.splitThinkTags(plain) === plain)
    }

    // Inputs below are the exact ones that failed in a real session.
    @Test fun `sdk_lookup understands how models phrase queries`() {
        val p = app.kiln.tools.ClassIndex::parseSdkQuery
        assertEquals("KStore" to null, p("class KStore"))
        assertEquals("KilnActivity" to "onCreate", p("KilnActivity onCreate"))
        assertEquals("ComponentActivity" to "onCreate", p("class ComponentActivity onCreate"))
        assertEquals("Modifier" to "padding", p("Modifier.padding"))
        assertEquals("androidx.compose.ui.Modifier" to "padding", p("androidx.compose.ui.Modifier.padding"))
        assertEquals("LazyColumn" to "items", p("LazyColumn#items"))
        assertEquals("app.kiln.kit.KilnActivity" to null, p("app.kiln.kit.KilnActivity"))
        assertEquals("app.kiln.kit.KStore" to null, p("app.kiln.kit KStore"))
        assertEquals("Icons.Filled.FlashlightOff" to null, p("Icons.Filled.FlashlightOff"))
    }

    @Test fun `tap targets match the way models describe them`() {
        fun n(text: String, desc: String = "", click: Boolean = false, y: Int = 0) =
            app.kiln.device.UiNode(text, desc, "", "android.view.View", click, false, null, true, 0, y, 10, y + 10)
        val screen = listOf(n("Today's Intake", y = 900), n("0 ml", y = 1000), n("", "Add 250ml", click = true, y = 1600),
            n("250 ml", y = 1700), n("Reset", click = true, y = 2000))
        val f = { t: String -> app.kiln.device.findNode(screen, t)?.top }
        assertEquals(1600, f("Button \"Add 250ml\""))
        assertEquals(1600, f("add 250ml"))
        assertEquals(2000, f("the Reset button"))
        assertEquals(900, f("Today's Intake"))
        assertEquals(null, f("the large circular water drop button"))
    }

    @Test fun `lint catches dollar-dot templates and unlabeled icon buttons`() {
        val dir = Files.createTempDirectory("kiln").toFile()
        File(dir, "src").mkdirs()
        File(dir, "src/A.kt").writeText("""
            val a = "Total: ${'$'}items.size"
            val ok1 = "Total: ${'$'}{items.size}"
            val ok2 = "Saved ${'$'}name.txt"
            val ok3 = "Price ${'$'}price"
            fun Row() { IconButton(onClick = {}) { Icon(Icons.Rounded.Delete, contentDescription = null) } }
            fun Row2() { IconButton(onClick = {}) { Icon(Icons.Rounded.Delete, contentDescription = "Delete milk") } }
        """.trimIndent())
        val d = app.kiln.build.Lint.run(app.kiln.build.Project(dir))
        assertEquals(2, d.size)
        assertTrue(d[0].message.contains("\${items.size}") && d[0].line == 1)
        assertTrue(d[1].message.contains("contentDescription") && d[1].line == 5)
    }

    @Test fun `context fit clears old tool output, keeps recent turns and the stored transcript`() {
        val big = "x".repeat(40_000)
        val msgs = (0 until 12).flatMap { i -> listOf(
            Msg("assistant", arrOf(listOf(obj("type" to "tool_use", "id" to "t$i", "name" to "read_file", "input" to obj())))),
            Msg("user", arrOf(listOf(obj("type" to "tool_result", "tool_use_id" to "t$i", "content" to "line one\n$big")))),
        ) }
        val fitted = app.kiln.agent.ContextFit.fit(msgs, "sys", window = 64_000)
        assertTrue(app.kiln.agent.ContextFit.estimateTokens(fitted, "sys") <= 64_000 * 0.7)
        assertTrue(fitted.first { it.role == "user" }.content.toString().contains("[cleared to fit the context window] line one"))
        assertTrue(fitted.last().content.toString().contains(big))          // recent output intact
        assertTrue(msgs[1].content.toString().contains(big))                // transcript untouched
        val small = msgs.take(2)
        assertTrue(app.kiln.agent.ContextFit.fit(small, "sys", 64_000) === small)   // under budget: untouched
    }

    @Test fun `project config can tune effort but never approvals hooks or caps`() {
        val dir = Files.createTempDirectory("kiln").toFile()
        File(dir, ".kiln").mkdirs()
        File(dir, ".kiln/config.json").writeText("""{"effort":"low","approval":{"shell":"allow"},"hooks":{"postTool:read_file":["shell"]},"dailyUsd":1e9,"sessionUsd":1e9}""")
        val m = app.kiln.agent.Settings().merged(dir)
        assertEquals("low", m.effort)
        assertTrue(m.approval.isEmpty() && m.hooks.isEmpty())
        assertEquals(app.kiln.agent.Settings().dailyUsd, m.dailyUsd, 0.0)
    }

    @Test fun `agent file writes cannot touch kiln state`() {
        val dir = Files.createTempDirectory("kiln").toFile()
        val p = app.kiln.build.Project(dir)
        for (bad in listOf(".kiln/config.json", ".kiln/tools.d/x.json", ".kiln/signing.p12", ".kiln"))
            assertTrue(bad, runCatching { p.resolveWritable(bad) }.isFailure)
        assertTrue(runCatching { p.resolveWritable("src/A.kt") }.isSuccess)
    }

    @Test fun `edit counting is non-overlapping and cheap`() {
        assertEquals(2, app.kiln.tools.occurrences("aaaa", "aa"))
        assertEquals(0, app.kiln.tools.occurrences("abc", ""))
        assertEquals(1, app.kiln.tools.occurrences("x".repeat(300_000) + "needle", "needle"))
    }

    @Test fun `new projects get distinct starter icons`() {
        val icons = listOf("water", "shopping", "flashlight", "habits", "notes", "timer").map { app.kiln.build.DefaultIcon.xml(it) }
        assertTrue(icons.toSet().size >= 5)
        assertTrue(icons.all { "M0,0h108v108h-108z" in it && "<vector" in it })
    }

    @Test fun `openai responses converts calls and outputs`() {
        val a = OpenAIResponsesAdapter(Profile("oa", "OA", Protocol.OPENAI_RESPONSES, "https://api.openai.com", caps = Caps()), "k")
        val body = a.buildBody(ModelRequest("gpt", "sys", transcript(), listOf(tool)))
        val input = body["input"] as JsonArray
        assertTrue(input.any { (it as JsonObject).str("type") == "function_call" && it.str("call_id") == "t1" })
        assertTrue(input.any { (it as JsonObject).str("type") == "function_call_output" })
        assertEquals("sys", body.str("instructions"))
    }

    @Test fun `turn snapshots review, restore and fork`() {
        val tmp = Files.createTempDirectory("kiln").toFile()
        val p = app.kiln.build.Project(File(tmp, "proj").apply { mkdirs() })
        File(p.dir, "kiln.json").writeText("{}")
        val a = File(p.src, "A.kt").apply { parentFile.mkdirs(); writeText("one") }
        val s = app.kiln.agent.Session.create(File(tmp, "sessions"), "proj", "sys")
        s.append(Msg("user", arrOf(listOf(obj("type" to "text", "text" to "change it")))))
        app.kiln.agent.Turns.snapshot(s, p, 0)
        a.writeText("two"); File(p.src, "B.kt").writeText("new")
        s.append(Msg("assistant", arrOf(listOf(obj("type" to "text", "text" to "done")))))
        val ch = app.kiln.agent.Turns.changes(s, p, 0).associate { it.path to it.kind }
        assertEquals(app.kiln.agent.Turns.Change.Kind.MODIFIED, ch["src/A.kt"])
        assertEquals(app.kiln.agent.Turns.Change.Kind.ADDED, ch["src/B.kt"])
        assertEquals("one", app.kiln.agent.Turns.before(s, 0, "src/A.kt"))
        // Reverting one file leaves the other alone.
        app.kiln.agent.Turns.restore(s, p, 0, listOf("src/B.kt"))
        assertFalse(File(p.src, "B.kt").exists()); assertEquals("two", a.readText())
        app.kiln.agent.Turns.restore(s, p, 0)
        assertEquals("one", a.readText())
        val f = app.kiln.agent.Turns.fork(s, File(tmp, "sessions"), 0)
        assertEquals(0, f.messages.size); assertEquals(2, s.messages.size)
        tmp.deleteRecursively()
    }

    @Test fun `security check ignores xml namespaces but flags real cleartext`() {
        val issues = app.kiln.tools.securityIssues(mapOf(
            "AndroidManifest.xml" to "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">",
            "src/Api.kt" to "val base = \"http://api.example.com\"",
        ), emptyList())
        assertEquals(1, issues.count { "cleartext" in it })
        assertTrue(issues.any { it.startsWith("src/Api.kt:1") })
    }

    @Test fun `git blob ids match git and the bundle config is valid protobuf`() {
        // printf 'hello' + newline | git hash-object --stdin
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", app.kiln.publish.GitHubSync.gitBlobSha(("hello" + 10.toChar()).toByteArray()))
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", app.kiln.publish.GitHubSync.gitBlobSha(ByteArray(0)))
        val cfg = app.kiln.build.AabPackager.bundleConfig()
        assertEquals(0x0A, cfg[0].toInt()); assertEquals(cfg.size - 2, cfg[1].toInt())
        assertEquals(app.kiln.build.AabPackager.BUNDLETOOL_VERSION, String(cfg, 4, cfg.size - 4))
    }

    @Test fun `tool_search loads matching deferred tools into the session`() = kotlinx.coroutines.runBlocking<Unit> {
        fun fake(n: String, d: String) = object : app.kiln.tools.Tool {
            override val name = n; override val description = d
            override val schema = obj(); override val traits = emptySet<app.kiln.tools.Trait>()
            override val deferred = true
            override suspend fun run(ctx: app.kiln.tools.ToolContext, input: JsonObject) = app.kiln.tools.ToolResult.ok("x")
        }
        val tmp = Files.createTempDirectory("kiln").toFile()
        val ctx = object : app.kiln.tools.ToolContext {
            override val project = app.kiln.build.Project(tmp)
            override val sessionId = "t"
            override val state = app.kiln.tools.SessionState()
            override fun progress(line: String) {}
            override suspend fun ask(question: String, options: List<String>) = ""
            override fun spill(text: String, maxChars: Int) = text
            override val spillDir = tmp
        }
        val search = app.kiln.tools.ToolSearchTool(listOf(fake("gh__create_issue", "[gh] Create an issue"),
            fake("gh__list_prs", "[gh] List pull requests"), fake("db__query", "[db] Run a SQL query")))
        val r = search.run(ctx, obj("query" to "create issue"))
        assertTrue(r.text.contains("gh__create_issue"))
        assertEquals(setOf("gh__create_issue"), ctx.state.loadedTools.toSet())
        assertTrue(search.run(ctx, obj("query" to "weather")).text.contains("No tool matches"))
        tmp.deleteRecursively()
    }

    @Test fun `attempts are hidden copies and keeping one restores the original package`() {
        val root = Files.createTempDirectory("kiln").toFile()
        val tpl = File(root, "tpl").apply { File(this, "src").mkdirs() }
        File(tpl, "kiln.json").writeText("{\"package\":\"{{package}}\",\"label\":\"{{label}}\"}")
        File(tpl, "src/Main.kt").writeText("package {{package}}" + System.lineSeparator() + "val v = 1")
        val p = app.kiln.build.Project.create(root, "notes", "Notes", tpl)
        val tries = app.kiln.agent.Attempts.create(root, p, 2, "make it blue")
        assertEquals(listOf("notes_try1", "notes_try2"), tries.map { it.name })
        assertTrue(app.kiln.agent.Attempts.isAttempt(tries[0].dir))
        val src2 = File(tries[1].src, "kiln/app/notes_try2/Main.kt")
        assertTrue(src2.readText().startsWith("package kiln.app.notes_try2"))
        src2.writeText(src2.readText().replace("val v = 1", "val v = 2"))
        File(tries[1].src, "kiln/app/notes_try2/Extra.kt").writeText("package kiln.app.notes_try2")
        val cp = app.kiln.agent.Attempts.adopt(p, tries[1])
        val main = File(p.src, "kiln/app/notes/Main.kt").readText()
        assertTrue(main.startsWith("package kiln.app.notes") && "val v = 2" in main && "try2" !in main)
        assertTrue(File(p.src, "kiln/app/notes/Extra.kt").isFile)
        assertEquals("Notes", p.meta().label); assertEquals("kiln.app.notes", p.meta().`package`)
        assertTrue(File(p.kilnDir, "checkpoints/$cp/src/kiln/app/notes/Main.kt").readText().contains("val v = 1"))
        app.kiln.agent.Attempts.discard(root, "notes")
        assertTrue(app.kiln.agent.Attempts.list(root, "notes").isEmpty())
        root.deleteRecursively()
    }

    @Test fun `play billing is merged only into apps that ask for it`() {
        val dir = Files.createTempDirectory("kiln").toFile()
        val app = File(dir, "AndroidManifest.xml").apply { writeText("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application/></manifest>") }
        val kit = File(dir, "kit.xml").apply { writeText("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">" +
            "<uses-permission android:name=\"com.android.vending.BILLING\"/><uses-permission android:name=\"android.permission.INTERNET\"/>" +
            "<queries><intent><action android:name=\"com.android.vending.billing.InAppBillingService.BIND\"/></intent></queries>" +
            "<application><activity android:name=\"com.android.billingclient.api.ProxyBillingActivity\"/>" +
            "<provider android:name=\"androidx.startup.InitializationProvider\"/></application></manifest>") }
        val plain = ManifestMerger.merge(app, kit, ProjectMeta("kiln.app.a", "A"))
        assertFalse("BILLING" in plain || "billingclient" in plain || "InAppBillingService" in plain)
        assertTrue("INTERNET" in plain && "InitializationProvider" in plain && "app.kiln" in plain)
        val paid = ManifestMerger.merge(app, kit, ProjectMeta("kiln.app.a", "A", permissions = listOf("com.android.vending.BILLING")))
        assertTrue("ProxyBillingActivity" in paid && "InAppBillingService" in paid && "com.android.vending.BILLING" in paid)
        dir.deleteRecursively()
    }
}
