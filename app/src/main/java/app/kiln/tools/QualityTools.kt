package app.kiln.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
    override val description = "Load a kit recipe (tested code + rules) before building a feature it covers — the list is in your instructions under Skills."
    override val schema = schema { str("name", "Skill name, e.g. permissions, notifications, background-work.") }
    override val traits = setOf(Trait.READ_ONLY, Trait.PARALLEL_SAFE)
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val n = input.req("name").trim().lowercase().removeSuffix(".md")
        return skills.read(n)?.let { ToolResult.ok(it, "skill $n") }
            ?: ToolResult.error("no skill \"$n\"; available: ${skills.names().joinToString()}")
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

/** Contrast between the darkest and lightest tenth of the pixels inside a text node. */
private fun contrast(shot: Bitmap, n: UiNode): Double? {
    val l = n.left.coerceIn(0, shot.width - 1); val r = n.right.coerceIn(l + 1, shot.width)
    val t = n.top.coerceIn(0, shot.height - 1); val b = n.bottom.coerceIn(t + 1, shot.height)
    if (r - l < 4 || b - t < 4) return null
    val step = max(1, ((r - l) * (b - t) / 4000.0).pow(0.5).toInt())
    val ls = mutableListOf<Double>()
    var y = t; while (y < b) { var x = l; while (x < r) { ls += lum(shot.getPixel(x, y)); x += step }; y += step }
    if (ls.size < 16) return null
    ls.sort()
    val dark = ls.take(ls.size / 10).average(); val light = ls.takeLast(ls.size / 10).average()
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
            if (Regex("""["']http://(?!localhost|127\.0\.0\.1|10\.0\.2\.2)""").containsMatchIn(line))
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
    private val run: suspend (criteria: String) -> Triple<String, app.kiln.llm.Usage, Double>,
    private val device: Device,
) : Tool {
    override val name = "qa_check"
    override val description = "Independent QA: a separate agent exercises the running app against your done criteria (taps, " +
        "types, reads the screen), records the session, and reports PASS/FAIL per criterion. Use it before calling work done."
    override val schema = schema { str("criteria", "Done criteria, one per line, each observable on the device.") }
    override val traits = setOf(Trait.NEEDS_BROKER, Trait.LONG_RUNNING)
    override val timeoutMs = 900_000L
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val criteria = input.req("criteria")
        ctx.progress("QA agent testing the app")
        val video = File(ctx.spillDir, "qa-${System.currentTimeMillis()}.mp4")
        val rec = device.testDisplay?.record(video, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val (report, _, usd) = try { run(criteria) } finally { }
        ctx.addCost(usd)
        val clip = runCatching { rec?.stop() }.getOrNull()
        val verdict = Regex("""VERDICT:\s*(PASS|FAIL)""", RegexOption.IGNORE_CASE).find(report)?.groupValues?.get(1)?.uppercase()
        return ToolResult(report.trim(), isError = verdict != "PASS", summary = "QA: ${verdict ?: "no verdict"}", video = clip?.path)
    }
}
