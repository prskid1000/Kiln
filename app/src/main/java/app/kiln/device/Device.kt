package app.kiln.device

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.kiln.core.ExecResult
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/** One node of the on-screen UI tree, compacted for the model. */
data class UiNode(
    val text: String, val desc: String, val id: String, val cls: String,
    val clickable: Boolean, val scrollable: Boolean, val checked: Boolean?, val enabled: Boolean,
    val left: Int, val top: Int, val right: Int, val bottom: Int,
) {
    val cx get() = (left + right) / 2
    val cy get() = (top + bottom) / 2
    fun label(): String = listOf(text, desc, id.substringAfter(":id/")).firstOrNull { it.isNotBlank() } ?: cls.substringAfterLast('.')
}

/**
 * Device actions for Kiln-built apps, through Warden. Every action that names
 * a package refuses anything outside `kiln.app.*` — enforced here, not in the
 * prompt.
 */
/**
 * Device actions through Warden. With a [testDisplay], everything visual (launch, input, UI tree,
 * screenshot) targets that invisible display instead of the user's screen — that's the instance
 * the agent's tools get. Install, logs, data and permissions are per-app and work the same.
 */
class Device(private val warden: Warden, val testDisplay: TestDisplay? = null) {
    private fun displayArgs(flag: String) = testDisplay?.let { listOf(flag, it.id().toString()) } ?: emptyList()
    private fun input(vararg args: String) = listOf("input") + displayArgs("-d") + args

    private fun guard(pkg: String) =
        require(pkg.startsWith("kiln.app.")) { "Kiln only touches its own apps (kiln.app.*), not $pkg" }

    /** Silent install by streaming the APK into `cmd package install -S`. */
    suspend fun install(apk: java.io.File): ExecResult {
        val bytes = apk.readBytes()
        suspend fun push() = warden.exec(listOf("cmd", "package", "install", "-r", "-t", "-S", bytes.size.toString()), stdin = bytes,
            timeoutMs = 300_000)
        val r = push()
        // A project recreated under an old name has a new signing key: Android refuses the update.
        // For Kiln's own apps the fix is a clean reinstall (the old copy's data goes with it).
        if (!r.ok && "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in r.all) {
            val pkg = Regex("package (kiln\\.app\\.[a-z0-9_]+)").find(r.all)?.groupValues?.get(1) ?: return r
            uninstall(pkg)
            return push().let { it.copy(out = it.out + "\n(reinstalled: the signing key changed, so the old copy and its data were removed)") }
        }
        return r
    }

    suspend fun uninstall(pkg: String): ExecResult { guard(pkg); return warden.exec(listOf("pm", "uninstall", pkg)) }

    /** Launch the app's launcher activity and wait for it to draw. */
    suspend fun launch(pkg: String): ExecResult {
        guard(pkg)
        val resolve = warden.exec(listOf("cmd", "package", "resolve-activity", "--brief",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", pkg))
        val component = resolve.out.lines().lastOrNull { '/' in it }?.trim()
            ?: return ExecResult(1, "", "no launcher activity in $pkg", 0)
        return warden.exec(listOf("am", "start", "-W", "-S") + displayArgs("--display") + listOf("-n", component))
    }

    suspend fun stop(pkg: String): ExecResult { guard(pkg); return warden.exec(listOf("am", "force-stop", pkg)) }
    suspend fun clearData(pkg: String): ExecResult { guard(pkg); return warden.exec(listOf("pm", "clear", pkg)) }
    suspend fun grant(pkg: String, permission: String): ExecResult {
        guard(pkg)
        val p = if ('.' in permission) permission else "android.permission.$permission"
        return warden.exec(listOf("pm", "grant", pkg, p))
    }

    /** A file in an app's private data dir (relative to /data/data/<pkg>). */
    data class DataFile(val path: String, val size: Long)

    private fun dataPath(path: String): String {
        val p = path.trim().removePrefix("./").removePrefix("/")
        require(p.isNotEmpty() && p.split('/').none { it == ".." }) { "bad path: $path" }
        return p
    }

    /** Every file in the app's private storage (builds are debuggable, so `run-as` works). */
    suspend fun dataFiles(pkg: String): Result<List<DataFile>> {
        guard(pkg)
        val r = warden.exec(listOf("run-as", pkg, "sh", "-c",
            "find . -type f ! -path './code_cache/*' -exec stat -c '%s|%n' {} +"))
        if (!r.ok && r.out.isBlank()) return Result.failure(IllegalStateException(r.err.trim().ifBlank { "run-as failed" }))
        return Result.success(r.out.lines().mapNotNull { l ->
            val i = l.indexOf('|'); if (i < 0) null else DataFile(l.substring(i + 1).removePrefix("./"), l.substring(0, i).toLongOrNull() ?: 0)
        }.sortedBy { it.path })
    }

    suspend fun readData(pkg: String, path: String): ByteArray? {
        guard(pkg)
        val (code, bytes) = warden.execBytes(listOf("run-as", pkg, "cat", dataPath(path)))
        return if (code == 0) bytes else null
    }

    suspend fun writeData(pkg: String, path: String, bytes: ByteArray): ExecResult {
        guard(pkg)
        return warden.exec(listOf("run-as", pkg, "sh", "-c", "cat > \"$1\"", "sh", dataPath(path)), stdin = bytes)
    }

    suspend fun deleteData(pkg: String, path: String): ExecResult {
        guard(pkg); return warden.exec(listOf("run-as", pkg, "rm", "-f", dataPath(path)))
    }

    suspend fun isInstalled(pkg: String): Boolean =
        warden.exec(listOf("pm", "path", pkg)).out.contains("package:")

    suspend fun pid(pkg: String): Int? = warden.exec(listOf("pidof", pkg)).out.trim().split(" ").firstOrNull()?.toIntOrNull()

    /** Device clock in logcat's -T format, used as a "since" marker. */
    suspend fun logMarker(): String = warden.exec(listOf("date", "+%m-%d %H:%M:%S.000")).out.trim()

    /**
     * Logcat for [pkg]: lines from its pid (or by tag when not running), since
     * [since] when given, at [minLevel] or above, newest [max] lines.
     */
    suspend fun logcat(pkg: String, since: String?, minLevel: String = "V", max: Int = 300): String {
        guard(pkg)
        val args = mutableListOf("logcat", "-d", "-v", "threadtime")
        if (since != null) args += listOf("-T", since)
        val pid = pid(pkg)
        if (pid != null) args += "--pid=$pid"
        args += "*:$minLevel"
        val r = warden.exec(args, timeoutMs = 30_000)
        val lines = r.out.lines().let { all ->
            if (pid != null) all else all.filter { pkg in it || "KILN-APP" in it || "KILN-CRASH" in it }
        }
        return lines.takeLast(max).joinToString("\n")
    }

    /** Newest crash of [pkg]: the kit's structured KILN-CRASH line, else the FATAL EXCEPTION block. */
    suspend fun lastCrash(pkg: String, since: String?): String? {
        guard(pkg)
        val args = mutableListOf("logcat", "-d", "-v", "threadtime", "-b", "main,crash")
        if (since != null) args += listOf("-T", since)
        val out = warden.exec(args, timeoutMs = 30_000).out
        out.lines().lastOrNull { "KILN-CRASH" in it && "\"pkg\":\"$pkg\"" in it }?.let { line ->
            val json = runCatching { JSONObject(line.substring(line.indexOf('{'))) }.getOrNull() ?: return line
            val frames = json.optJSONArray("frames")
            val own = (0 until (frames?.length() ?: 0)).map { frames!!.getString(it) }
            return buildString {
                appendLine("${json.optString("type")}: ${json.optString("message")}")
                if (json.optString("rootType") != json.optString("type"))
                    appendLine("root cause: ${json.optString("rootType")}: ${json.optString("rootMessage")}")
                appendLine("thread: ${json.optString("thread")}")
                // App frames first: those are the ones the agent can fix.
                own.sortedBy { if (it.startsWith(pkg)) 0 else 1 }.take(25).forEach { appendLine("  at $it") }
            }
        }
        val lines = out.lines()
        val start = lines.indexOfLast { "FATAL EXCEPTION" in it }
        if (start < 0) return null
        val block = lines.drop(start).takeWhile { "AndroidRuntime" in it }.take(60)
        return if (block.any { pkg in it }) block.joinToString("\n") { it.substringAfter("AndroidRuntime: ") } else null
    }

    /** PNG screenshot, scaled to [maxSide] on the long edge (models don't need 2780 px). */
    suspend fun screenshot(maxSide: Int = 1280): ByteArray? {
        testDisplay?.let { it.id(); return it.capture(maxSide) }
        val (code, png) = warden.execBytes(listOf("screencap", "-p"))
        if (code != 0 || png.isEmpty()) return null
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** The on-screen UI tree (uiautomator), compacted to meaningful nodes. */
    /**
     * The on-screen UI tree. With [expect] (the app's package), waits until that app's nodes are
     * there — a just-created test display briefly dumps the default display instead — and on the
     * test display never returns another app's tree.
     */
    suspend fun uiTree(expect: String? = null): List<UiNode> {
        val path = "/data/local/tmp/kiln-ui.xml"
        val display = testDisplay?.let { " --display ${it.id()}" } ?: ""
        // Remove the previous dump first: when a fresh display isn't ready, uiautomator writes
        // nothing and we'd read the last screen's tree. Retry briefly while it settles.
        var xml = ""
        for (attempt in 0 until 6) {
            val r = warden.exec(listOf("sh", "-c", "rm -f $path; uiautomator dump$display $path >/dev/null 2>&1; cat $path 2>/dev/null"), timeoutMs = 30_000)
            xml = r.out.substring(r.out.indexOf('<').coerceAtLeast(0))
            val ready = xml.startsWith("<") && "<node" in xml && (expect == null || "package=\"$expect\"" in xml)
            if (ready) break
            // Accessibility reports windows of the focused display only; a no-op key event gives
            // the test display focus (the user's next touch takes it back).
            testDisplay?.let { warden.exec(input("keyevent", "KEYCODE_UNKNOWN")) }
            kotlinx.coroutines.delay(300)
        }
        if (expect != null && testDisplay != null && "package=\"$expect\"" !in xml) return emptyList()
        if (!xml.startsWith("<")) return emptyList()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val nodes = doc.getElementsByTagName("node")
        val out = mutableListOf<UiNode>()
        val bounds = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            val m = bounds.find(e.getAttribute("bounds")) ?: continue
            val (l, t, rr, b) = m.destructured
            val n = UiNode(
                text = e.getAttribute("text"), desc = e.getAttribute("content-desc"),
                id = e.getAttribute("resource-id"), cls = e.getAttribute("class"),
                clickable = e.getAttribute("clickable") == "true", scrollable = e.getAttribute("scrollable") == "true",
                checked = if (e.getAttribute("checkable") == "true") e.getAttribute("checked") == "true" else null,
                enabled = e.getAttribute("enabled") == "true",
                left = l.toInt(), top = t.toInt(), right = rr.toInt(), bottom = b.toInt(),
            )
            if (n.text.isNotBlank() || n.desc.isNotBlank() || n.clickable || n.scrollable || n.checked != null) out += n
        }
        return out
    }

    /** Find a node by visible text / content description / resource id (case-insensitive contains). */
    fun find(nodes: List<UiNode>, target: String): UiNode? = findNode(nodes, target)

    suspend fun tap(x: Int, y: Int) = warden.exec(input("tap", "$x", "$y"))
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int = 300) =
        warden.exec(input("swipe", "$x1", "$y1", "$x2", "$y2", "$ms"))
    suspend fun key(key: String) = warden.exec(input("keyevent", key.uppercase().let { if (it.startsWith("KEYCODE_")) it else "KEYCODE_$it" }))
    /** `input text` needs spaces as %s and shell metacharacters escaped. */
    suspend fun type(text: String) = warden.exec(input("text", text.replace(" ", "%s")
        .replace(Regex("""([\\'"`$&|;<>()*?!#~\[\]{}])"""), "\\\\$1")))
    /** Package of the activity in front right now, or null if it can't be told. */
    suspend fun foregroundPackage(): String? {
        val out = warden.exec(listOf("dumpsys", "activity", "activities")).out
        // On the test display, only that display's section counts.
        // Each display has its own section; the first "topResumed" line follows input focus, not the screen.
        val shown = testDisplay?.id() ?: 0
        val section = out.substringAfter("Display #$shown ", "").substringBefore("\nDisplay #").ifEmpty { out }
        val line = section.lineSequence().firstOrNull { "topResumedActivity" in it || "ResumedActivity" in it } ?: return null
        return Regex("""\s([a-zA-Z0-9_.]+)/""").find(line)?.groupValues?.get(1)
    }

    suspend fun screenSize(): Pair<Int, Int> {
        testDisplay?.let { it.id(); return it.width to it.height }
        val m = Regex("""(\d+)x(\d+)""").findAll(warden.exec(listOf("wm", "size")).out).lastOrNull() ?: return 1080 to 2400
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    /** Kiln-built apps currently installed. */
    suspend fun installedApps(): List<String> =
        warden.exec(listOf("pm", "list", "packages", "kiln.app.")).out.lines()
            .mapNotNull { it.removePrefix("package:").trim().takeIf { p -> p.startsWith("kiln.app.") } }.sorted()
}

/**
 * The element a model means by [target]. Models write targets the way they'd describe them —
 * `Button "Add 250ml"`, `the Save button`, `'Reset'` — so roles, quotes and filler words are
 * stripped, then exact, contains, and best word-overlap matches are tried; clickable wins ties.
 */
fun findNode(nodes: List<UiNode>, target: String): UiNode? {
    val filler = setOf("button", "btn", "text", "textview", "view", "image", "imageview", "icon", "the", "a", "an",
        "tab", "label", "field", "edittext", "switch", "checkbox", "item", "element", "on", "labeled", "labelled", "called")
    val quoted = Regex("[\"'“”‘’]([^\"'“”‘’]+)[\"'“”‘’]").find(target)?.groupValues?.get(1)
    val words = target.lowercase().split(Regex("[^\\p{L}\\p{N}+.%-]+")).filter { it.isNotBlank() && it !in filler }
    val candidates = listOfNotNull(quoted, target, words.joinToString(" ")).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
    fun hay(n: UiNode) = listOf(n.text, n.desc, n.id.substringAfter(":id/")).map { it.lowercase() }
    val ordered = nodes.sortedBy { if (it.clickable) 0 else 1 }
    for (c in candidates) ordered.firstOrNull { n -> hay(n).any { it == c } }?.let { return it }
    for (c in candidates) ordered.firstOrNull { n -> hay(n).any { it.isNotEmpty() && (it.contains(c) || (c.length > 3 && c.contains(it) && it.length > 2)) } }?.let { return it }
    if (words.isEmpty()) return null
    val best = ordered.map { n -> n to words.count { w -> hay(n).any { it.contains(w) } } }.maxByOrNull { it.second } ?: return null
    return best.first.takeIf { best.second * 2 >= words.size && best.second > 0 }
}
