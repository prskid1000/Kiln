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
}
