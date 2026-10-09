package app.kiln.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import app.kiln.core.a
import app.kiln.core.str
import app.kiln.device.Device
import app.kiln.device.UiNode
import app.kiln.device.Warden
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private fun JsonObject.req(k: String) = str(k) ?: throw IllegalArgumentException("missing '$k'")

// ---------------------------------------------------------------- skills

/** Kit recipes shipped with Kiln (Markdown files in assets/skills): listed by one line, loaded on demand. */
class Skills(private val context: Context) {
    fun names(): List<String> = context.assets.list("skills")?.filter { it.endsWith(".md") }?.map { it.removeSuffix(".md") }?.sorted() ?: emptyList()
    fun read(name: String): String? = runCatching { context.assets.open("skills/$name.md").use { it.readBytes().decodeToString() } }.getOrNull()
    /** "- name: description" lines for the system prompt. */
    fun index(): String = names().joinToString("\n") { n -> "- " + (read(n)?.lineSequence()?.firstOrNull()?.removePrefix("# ")?.trim() ?: n) }
}

class LoadSkillTool(private val skills: Skills) : Tool {
    override val name = "load_skill"
    override val description = "Load kit recipes (tested code + rules) before building the features they cover — load every skill you'll " +
        "need in ONE call, e.g. names: [\"architecture\", \"persistence\", \"charts\"]. The list is in your instructions under Skills."
    override val schema = schema {
        raw("names", app.kiln.core.obj("type" to "array", "items" to app.kiln.core.obj("type" to "string"),
            "description" to "Skill names, e.g. [\"architecture\", \"notifications\"]."))
    }
    override val traits = setOf(Trait.READ_ONLY, Trait.PARALLEL_SAFE)
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        // Runs spent ~7 steps loading skills one by one; a list loads them in one.
        val names = (input.a("names")?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: listOfNotNull(input.str("name")))
            .map { it.trim().lowercase().removeSuffix(".md") }.filter { it.isNotEmpty() }.distinct()
        if (names.isEmpty()) return ToolResult.error("give names: [\"architecture\", …]; available: ${skills.names().joinToString()}")
        val found = names.mapNotNull { n -> skills.read(n)?.let { n to it } }
        val missing = names - found.map { it.first }.toSet()
        if (found.isEmpty()) return ToolResult.error("no skill ${missing.joinToString { "\"$it\"" }}; available: ${skills.names().joinToString()}")
        return ToolResult.ok(found.joinToString("\n\n---\n\n") { it.second } +
            (if (missing.isEmpty()) "" else "\n\n(Not found: ${missing.joinToString()}. Available: ${skills.names().joinToString()})"),
            "skills ${found.joinToString { it.first }}")
    }
}

// ---------------------------------------------------------------- learned rules

/** The agent proposes a project rule after a correction; the user saves or dismisses it in the chat. */
class ProposeRuleTool : Tool {
    override val name = "propose_rule"
    override val description = "When the user corrects you on something that will matter again in this project (a preference, a " +
        "convention, a mistake to avoid), propose it as a one-line rule. The user decides whether to save it to project memory."
    override val schema = schema { str("rule", "The rule, imperative and specific, e.g. \"Show volumes in ml, never oz\"."); str("why", "What prompted it.", required = false) }
    override val traits = setOf(Trait.READ_ONLY)
    override suspend fun run(ctx: ToolContext, input: JsonObject) =
        ToolResult.ok("Proposed to the user: ${input.req("rule")}. Don't wait for their answer; continue the task.", "rule proposed")
}

// ---------------------------------------------------------------- screen comparison

/** Put a target image (attached mockup/screenshot) next to the current screen and score how close they are. */
class CompareScreenTool(w: Warden, d: Device) : DeviceTool(w, d) {
    override val name = "compare_screen"
    override val description = "Compare the app's current screen with a target image in the project (e.g. an attachment the user " +
        "sent): returns both side by side and a rough similarity score. Iterate (edit → run_app → compare_screen) to match a design."
    override val schema = schema { str("target", "Project-relative path of the target image, e.g. attachments/mockup.png.") }
    override val traits = setOf(Trait.NEEDS_BROKER, Trait.READ_ONLY, Trait.RETURNS_IMAGE)
    override suspend fun exec(ctx: ToolContext, input: JsonObject): ToolResult = withContext(Dispatchers.Default) {
        val f = ctx.project.resolve(input.req("target"))
        val target = BitmapFactory.decodeFile(f.path) ?: return@withContext ToolResult.error("not an image: ${input.str("target")}")
        val shot = device.screenshot(1280)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            ?: return@withContext ToolResult.error("no screenshot (is the app running? use run_app first)")
        val h = 960
        fun fit(b: Bitmap) = Bitmap.createScaledBitmap(b, max(1, b.width * h / b.height), h, true)
        val a = fit(target); val c = fit(shot)
        val out = Bitmap.createBitmap(a.width + c.width + 24, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(Color.rgb(22, 24, 38)); drawBitmap(a, 0f, 0f, null); drawBitmap(c, a.width + 24f, 0f, null) }
        val score = similarity(target, shot)
        val png = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.PNG, 90, it) }.toByteArray()
        ToolResult("Left: target. Right: current screen. Similarity ≈ ${score}% (layout and tone at low resolution — " +
            "aim for structure and spacing first; ≥ 85% is usually close).", listOf(png), summary = "similarity $score%")
    }

    /** Mean luminance difference on a 36×64 grid, as a percentage similarity. */
    private fun similarity(a: Bitmap, b: Bitmap): Int {
        val w = 36; val h = 64
        val x = Bitmap.createScaledBitmap(a, w, h, true); val y = Bitmap.createScaledBitmap(b, w, h, true)
        var diff = 0.0
        for (j in 0 until h) for (i in 0 until w) diff += abs(lum(x.getPixel(i, j)) - lum(y.getPixel(i, j)))
        return (100 - diff / (w * h) * 100).toInt().coerceIn(0, 100)
    }
}

internal fun lum(c: Int): Double {
    fun ch(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
    return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
}

// ---------------------------------------------------------------- UI / accessibility check

/** Android Studio's "UI check", on the running app: touch targets, labels, contrast, overlaps. */
class UiCheckTool(w: Warden, d: Device) : DeviceTool(w, d) {
    override val name = "ui_check"
    override val description = "Accessibility and layout check of the screen now: touch targets under 48dp, buttons without a " +
        "label, text with low contrast (measured from pixels), overlapping tap targets. Fix what it reports, then check again."
    override val schema = schema { }
    override val traits = setOf(Trait.NEEDS_BROKER, Trait.READ_ONLY)
    override suspend fun exec(ctx: ToolContext, input: JsonObject): ToolResult = withContext(Dispatchers.Default) {
        val nodes = device.uiTree(pkg(ctx))
        if (nodes.isEmpty()) return@withContext ToolResult.error("nothing on screen from ${pkg(ctx)} — run_app or launch it first")
        val density = device.density()
        val minPx = (48 * density / 160f).toInt()
        val shot = device.screenshot(4096)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        val issues = checkUi(nodes, minPx, shot)
        ToolResult.ok(if (issues.isEmpty()) "No issues found on this screen (${nodes.size} elements checked)."
            else "${issues.size} issue(s):\n" + issues.joinToString("\n") { "- $it" }, if (issues.isEmpty()) "no UI issues" else "${issues.size} UI issues")
    }
}

/** Pure part of [UiCheckTool], so it can be unit-tested. */
fun checkUi(nodes: List<UiNode>, minPx: Int, shot: Bitmap?): List<String> {
    val out = mutableListOf<String>()
    val clickable = nodes.filter { it.clickable && it.enabled }
    for (n in clickable) {
        val w = n.right - n.left; val h = n.bottom - n.top
        if (w < minPx || h < minPx) out += "tap target \"${n.label()}\" at [${n.cx},${n.cy}] is ${w}×${h}px — at least ${minPx}px (48dp) each way"
        if (n.text.isBlank() && n.desc.isBlank()) out += "button at [${n.cx},${n.cy}] has no label — give it a contentDescription (or visible text)"
    }
    for (i in clickable.indices) for (j in i + 1 until clickable.size) {
        val a = clickable[i]; val b = clickable[j]
        val ox = min(a.right, b.right) - max(a.left, b.left); val oy = min(a.bottom, b.bottom) - max(a.top, b.top)
        val inside = (a.left >= b.left && a.right <= b.right && a.top >= b.top && a.bottom <= b.bottom) ||
            (b.left >= a.left && b.right <= a.right && b.top >= a.top && b.bottom <= a.bottom)
        if (ox > 8 && oy > 8 && !inside) out += "tap targets \"${a.label()}\" and \"${b.label()}\" overlap"
    }
    if (shot != null) for (n in nodes.filter { it.text.isNotBlank() }) {
        val r = contrast(shot, n) ?: continue
        if (r < 4.5) out += "text \"${n.text.take(30)}\" has contrast ${"%.1f".format(r)}:1 — needs 4.5:1 (3:1 if large)"
    }
    return out.distinct().take(40)
}

/**
 * Contrast between the darkest and lightest few pixels inside a text node. Glyphs cover only a small
 * part of a button or field, so averaging a tenth of the pixels mixes antialiased edges into the
 * text colour and reports good contrast as poor; the extremes are the text and its background.
 */
private fun contrast(shot: Bitmap, n: UiNode): Double? {
    val l = n.left.coerceIn(0, shot.width - 1); val r = n.right.coerceIn(l + 1, shot.width)
    val t = n.top.coerceIn(0, shot.height - 1); val b = n.bottom.coerceIn(t + 1, shot.height)
    if (r - l < 4 || b - t < 4) return null
    val step = max(1, ((r - l) * (b - t) / 4000.0).pow(0.5).toInt())
    val ls = mutableListOf<Double>()
    var y = t; while (y < b) { var x = l; while (x < r) { ls += lum(shot.getPixel(x, y)); x += step }; y += step }
    if (ls.size < 16) return null
    ls.sort()
    val k = maxOf(3, ls.size / 50)
    val dark = ls.take(k).average(); val light = ls.takeLast(k).average()
    if (light - dark < 0.01) return null   // a flat area: no text pixels sampled
    return (light + 0.05) / (dark + 0.05)
}

// ---------------------------------------------------------------- security check

/** Static checks of the project before it ships: secrets in source, cleartext, risky components, permissions. */
class SecurityCheckTool : Tool {
    override val name = "security_check"
    override val description = "Scan the project for release risks: API keys/tokens in source, http:// URLs, permissions declared " +
        "but not used, exported components, WebView JavaScript bridges. Run before calling an app done."
    override val schema = schema { }
    override val traits = setOf(Trait.READ_ONLY, Trait.PARALLEL_SAFE)
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val issues = securityIssues(ctx.project.files().filter { it.extension in setOf("kt", "xml", "json", "properties") }
            .associate { ctx.project.rel(it) to it.readText() }, runCatching { ctx.project.meta().permissions }.getOrDefault(emptyList()))
        ToolResult.ok(if (issues.isEmpty()) "No security issues found." else "${issues.size} issue(s):\n" + issues.joinToString("\n") { "- $it" },
            if (issues.isEmpty()) "no security issues" else "${issues.size} security issues")
    }
}

private val SECRET_PATTERNS = listOf(
    "Google API key" to Regex("""AIza[0-9A-Za-z_\-]{30,}"""),
    "OpenAI/Anthropic-style key" to Regex("""sk-(?:ant-)?[A-Za-z0-9_\-]{20,}"""),
    "GitHub token" to Regex("""gh[pousr]_[A-Za-z0-9]{30,}"""),
    "AWS access key" to Regex("""AKIA[0-9A-Z]{16}"""),
    "Stripe secret key" to Regex("""sk_live_[0-9A-Za-z]{20,}"""),
    "hard-coded secret" to Regex("""(?i)(api[_-]?key|secret|token|password)\s*[:=]\s*"[^"\s]{12,}""""),
)

/** What a permission is for, as code that would use it. */
private val PERMISSION_USE = mapOf(
    "CAMERA" to listOf("Camera", "TakePicture", "CameraManager"),
    "ACCESS_FINE_LOCATION" to listOf("LocationManager", "getCurrentLocation", "FusedLocation"),
    "ACCESS_COARSE_LOCATION" to listOf("LocationManager", "getCurrentLocation", "FusedLocation"),
    "RECORD_AUDIO" to listOf("AudioRecord", "MediaRecorder", "SpeechRecognizer"),
    "READ_CONTACTS" to listOf("ContactsContract"),
    "ACTIVITY_RECOGNITION" to listOf("TYPE_STEP_COUNTER", "TYPE_STEP_DETECTOR"),
    "BODY_SENSORS" to listOf("TYPE_HEART_RATE"),
)

fun securityIssues(files: Map<String, String>, permissions: List<String>): List<String> {
    val out = mutableListOf<String>()
    for ((path, text) in files) {
        text.lineSequence().forEachIndexed { i, line ->
            for ((what, re) in SECRET_PATTERNS) if (re.containsMatchIn(line))
                out += "$path:${i + 1}: $what in source — anything in an APK can be extracted; use per-project secrets and a server/proxy for paid APIs"
            if (Regex("""["']http://(?!localhost|schemas\.android\.com|www\.w3\.org|ns\.adobe\.com|127\.0\.0\.1|10\.0\.2\.2)""").containsMatchIn(line))
                out += "$path:${i + 1}: cleartext http:// URL — use https (cleartext is blocked on Android 9+)"
            if ("addJavascriptInterface" in line) out += "$path:${i + 1}: WebView JavaScript bridge — only load your own content in that WebView"
        }
        if (path.endsWith("AndroidManifest.xml"))
            Regex("""<(activity|service|receiver|provider)\b[^>]*android:exported="true"[^>]*>""").findAll(text)
                .filterNot { "android.intent.action.MAIN" in text.substring(it.range.first).substringBefore("</") }
                .forEach { out += "$path: exported ${it.groupValues[1]} — make sure it should be callable by other apps" }
    }
    val code = files.filterKeys { it.endsWith(".kt") }.values.joinToString("\n")
    for (p in permissions) {
        val short = p.substringAfterLast('.')
        val uses = PERMISSION_USE[short] ?: continue
        if (uses.none { it in code }) out += "permission $short is declared but nothing uses it — remove it (set_app_meta)"
    }
    return out.distinct()
}

// ---------------------------------------------------------------- QA pass

/**
 * A separate QA agent with fresh context checks each done criterion by using the app on the test
 * display, while the screen is recorded. The builder doesn't grade its own work.
 */
class QaCheckTool(
    /** Runs the QA agent (Agents.QA) on the criteria; its steps go into the chat through the ToolContext. */
    private val run: suspend (criteria: String, ctx: ToolContext, onStep: (String) -> Unit) -> Triple<String, app.kiln.llm.Usage, Double>,
    private val device: Device,
    /** Builds and installs the latest code before QA; returns why it couldn't (QA doesn't run then), or null. */
    private val prepare: suspend (ToolContext) -> String? = { null },
) : Tool {
    override val name = "qa_check"
    override val description = "Independent QA: a separate agent exercises the running app against your done criteria (taps, " +
        "types, reads the screen), records the session, and reports PASS/FAIL per criterion. It builds and installs your latest code itself — after a fix, call it directly (no build or run_app first). Use it before calling work done."
    override val schema = schema { str("criteria", "Done criteria, one per line, each observable on the device.") }
    override val traits = setOf(Trait.NEEDS_BROKER, Trait.LONG_RUNNING)
    override val timeoutMs = 900_000L
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        // A re-run focuses on what failed or what the code changes could affect (run 8 re-tested the
        // whole app three times, ~6 min each).
        val memoryFile = File(ctx.project.dir, ".kiln/qa-last.json")
        val last = runCatching { app.kiln.core.KJ.decodeFromString(QaMemory.serializer(), memoryFile.readText()) }.getOrNull()
        val snapshot = sourceSnapshot(ctx.project)
        prepare(ctx)?.let { return ToolResult.error(it) }
        // Re-runs are focused by Kiln, not by asking the model (run 13's QA was told to carry passes over and re-tested
        // everything anyway): only failed and new criteria go to the QA agent, plus a quick look at every screen.
        val plan = qaPlan(criteriaLines(input.req("criteria")), last)
        val criteria = if (plan.carried.isEmpty()) input.req("criteria") else
            "Test these criteria fully:\n" + plan.test.joinToString("\n") { "- $it" } +
                "\n\nThese passed in the last QA run and are carried over — don't test them:\n" + plan.carried.joinToString("\n") { "- ${it.first}" } +
                "\n\nThen a quick smoke check: open each main screen once and confirm it shows and nothing crashes (no deep testing). " +
                "Report one line per criterion you tested, then 'SMOKE: OK' or 'SMOKE: <what broke>', then VERDICT: FAIL if a tested " +
                "criterion failed or the smoke check found a problem, else VERDICT: PASS."
        ctx.progress(if (plan.carried.isEmpty()) "QA agent testing the app" else "QA agent re-testing ${plan.test.size} criteria (${plan.carried.size} carried over)")
        val video = File(ctx.spillDir, "qa-${System.currentTimeMillis()}.mp4")
        val rec = device.testDisplay?.record(video, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        // The QA agent's own steps: live in the chat while it works, and listed under its report.
        val trail = java.util.Collections.synchronizedList(mutableListOf<String>())
        // Every QA step — its tool call, result and screenshot — nested under this call in the chat, live.
        val (report, _, usd) = run(criteria, ctx) { line -> trail += line; ctx.progress("QA · step ${trail.size}: $line") }
        ctx.addCost(usd)
        val clip = runCatching { rec?.stop() }.getOrNull()
        val verdict = Regex("""VERDICT:\s*(PASS|FAIL)""", RegexOption.IGNORE_CASE).find(report)?.groupValues?.get(1)?.uppercase()
        // Carried criteria go back into the report and the memory as passes.
        val carriedLines = plan.carried.map { "PASS — ${it.first} — carried over from the last QA run" }
        val fullReport = if (carriedLines.isEmpty()) report.trim() else
            report.trim() + "\n\nCarried over from the last QA run (passed then):\n" + carriedLines.joinToString("\n")
        // Remember the per-criterion lines and what the source looked like, for the next run.
        val lines = qaResultLines(report) + carriedLines
        if (lines.isNotEmpty()) runCatching {
            memoryFile.parentFile?.mkdirs()
            memoryFile.writeText(app.kiln.core.KJ.encodeToString(QaMemory.serializer(), QaMemory(lines, snapshot, System.currentTimeMillis())))
        }
        return ToolResult(fullReport, isError = verdict != "PASS", summary = "QA: ${verdict ?: "no verdict"}", video = clip?.path,
            detail = trail.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n").ifBlank { null })
    }
}

/** What the last QA run found, and a fingerprint of the source it tested. */
@kotlinx.serialization.Serializable
data class QaMemory(val results: List<String>, val files: Map<String, Int>, val at: Long)

/**
 * The per-criterion lines of a QA report, whatever their bullet: "PASS — …", "- **FAIL** — …", "1. PASS — …".
 * (Run 10's report was numbered; nothing was remembered and the next run re-tested everything.)
 */
internal fun qaResultLines(report: String): List<String> =
    report.lines().map { it.trim().replace(Regex("""^(?:[-*•]\s+|\d+[.)]\s*)+"""), "") }
        .filter { Regex("""^\**(PASS|FAIL)\b""").containsMatchIn(it) }

/** Hash of every source and resource file, by project path. */
internal fun sourceSnapshot(project: app.kiln.build.Project): Map<String, Int> =
    project.files().filter { f -> f.isFile && (f.extension in setOf("kt", "xml", "json")) && "/.kiln/" !in f.path.replace('\\', '/') }
        .associate { project.rel(it) to it.readBytes().contentHashCode() }

/** The criteria in a qa_check input, one per item: lines, or "1. … 2. …" run together on one line. */
internal fun criteriaLines(text: String): List<String> =
    text.replace(Regex("""\s+(?=\d+[.)]\s)"""), "\n").lines()
        .map { it.trim().replace(Regex("""^(?:[-*•]\s+|\d+[.)]\s*)+"""), "").trim() }.filter { it.length > 3 }

/** What a QA run tests fully, and which criteria it carries over as passes from [QaMemory] (with the old line). */
internal data class QaPlan(val test: List<String>, val carried: List<Pair<String, String>>)

/**
 * After a failed QA run, re-test only what failed or is new; a criterion that passed last time is carried
 * over. With no earlier run, or nothing failed, everything is tested.
 */
internal fun qaPlan(criteria: List<String>, last: QaMemory?): QaPlan {
    fun verdict(line: String) = Regex("""^\**(PASS|FAIL)""").find(line)?.groupValues?.get(1)
    if (last == null || last.results.none { verdict(it) == "FAIL" } || criteria.isEmpty()) return QaPlan(criteria, emptyList())
    fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
    val test = mutableListOf<String>(); val carried = mutableListOf<Pair<String, String>>()
    for (c in criteria) {
        val w = words(c)
        val best = last.results.maxByOrNull { (words(it) intersect w).size }
        val overlap = best?.let { if (w.isEmpty()) 0.0 else (words(it) intersect w).size.toDouble() / w.size } ?: 0.0
        if (best != null && overlap >= 0.6 && verdict(best) == "PASS") carried += c to best else test += c
    }
    return if (test.isEmpty()) QaPlan(criteria, emptyList()) else QaPlan(test, carried)
}

/** A full-resolution screenshot saved into the project, for the store listing. */
class SaveScreenshotTool(w: Warden, d: Device) : DeviceTool(w, d) {
    override val name = "save_screenshot"
    override val description = "Save what the app shows now as a full-resolution PNG in the project, under store/ " +
        "(e.g. store/screenshots/1-home.png) — for the Play listing. Fill the app with realistic content first."
    override val schema = schema { str("path", "Where to save it, under store/, ending in .png.") }
    override suspend fun exec(ctx: ToolContext, input: JsonObject): ToolResult {
        val path = input.str("path")?.trim()?.trimStart('/') ?: return ToolResult.error("missing path")
        if (!path.startsWith("store/") || !path.endsWith(".png")) return ToolResult.error("save under store/ as a .png, e.g. store/screenshots/1-home.png")
        if (device.foregroundPackage() != pkg(ctx)) return ToolResult.error("${pkg(ctx)} is not on screen — launch it first")
        val png = device.screenshot(maxSide = 4096) ?: return ToolResult.error("screenshot failed")
        val f = ctx.project.resolveWritable(path)
        f.parentFile?.mkdirs(); f.writeBytes(png)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, o)
        return ToolResult.ok("saved $path (${o.outWidth}x${o.outHeight})", "saved $path")
    }
}

/** Finds deferred tools (from large MCP servers) by keyword and loads them into the session. */
class ToolSearchTool(private val hidden: List<Tool>) : Tool {
    override val name = "tool_search"
    override val description = "Find and load more tools. ${hidden.size} tools from connected MCP servers " +
        "(${hidden.map { it.name.substringBefore("__") }.distinct().joinToString()}) are hidden until you search: give keywords for " +
        "what you need; matching tools become callable from your next step."
    override val schema = schema { str("query", "Keywords, e.g. \"create issue\" or \"query database\".") }
    override val traits = setOf(Trait.READ_ONLY, Trait.PARALLEL_SAFE)
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val words = (input.str("query") ?: "").lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }
        if (words.isEmpty()) return ToolResult.error("give some keywords")
        val hits = hidden.map { t ->
            val name = t.name.lowercase(); val desc = t.description.lowercase()
            t to words.sumOf { w -> (if (w in name) 3 else 0) + (if (w in desc) 1 else 0) }
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(5).map { it.first }
        if (hits.isEmpty()) return ToolResult.ok("No tool matches \"${input.str("query")}\". Hidden tools: " +
            hidden.joinToString { it.name }.take(1500), "no match")
        hits.forEach { ctx.state.loadedTools += it.name }
        return ToolResult.ok("Loaded — callable from your next step:\n" +
            hits.joinToString("\n") { "- ${it.name}: ${it.description.take(200)}" }, "loaded ${plural(hits.size, "tool")}")
    }
}
