package app.kiln.device

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.kiln.core.ExecResult
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/** One node of the on-screen UI tree, compacted for the model. */
data class UiNode(
    val text: String, val desc: String, val id: String, val cls: String,
    val clickable: Boolean, val scrollable: Boolean, val checked: Boolean?, val enabled: Boolean,
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    /** Has input focus (a text field being typed into); null when the screen data doesn't say (apps on an older kit). */
    val focused: Boolean? = null,
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
class Device(private val warden: Warden, val testDisplay: TestDisplay? = null,
             /** Remembers the user's crash-dialog setting across a Kiln restart. */ private val dialogsFile: java.io.File? = null) {
    private fun displayArgs(flag: String) = testDisplay?.let { listOf(flag, it.id().toString()) } ?: emptyList()
    private fun input(vararg args: String) = listOf("input") + displayArgs("-d") + args

    private fun guard(pkg: String) =
        require(pkg.startsWith("kiln.app.")) { "Kiln only touches its own apps (kiln.app.*), not $pkg" }

    /** Silent install by streaming the APK into `cmd package install -S`. */
    suspend fun install(apk: java.io.File): ExecResult {
        // The debug APK is named after its package (build/<package>.apk). Only Kiln's own apps are installed, and a
        // signature clash reinstalls only that same package — kiln.json is agent-editable, and naming another
        // project's package must not uninstall that project's app and data.
        val own = apk.nameWithoutExtension
        if (!own.startsWith("kiln.app.")) return ExecResult(1, "", "Kiln only installs its own apps (kiln.app.*), not $own", 0)
        val bytes = apk.readBytes()
        suspend fun push() = warden.exec(listOf("cmd", "package", "install", "-r", "-t", "-S", bytes.size.toString()), stdin = bytes,
            timeoutMs = 300_000)
        val r = push()
        // A project recreated under an old name has a new signing key: Android refuses the update.
        // For Kiln's own apps the fix is a clean reinstall (the old copy's data goes with it).
        if (!r.ok && "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in r.all) {
            // "Existing package …" (older Android) or "Package … signatures do not match" (newer): either case.
            val pkg = Regex("package (kiln\\.app\\.[a-z0-9_]+)", RegexOption.IGNORE_CASE).find(r.all)?.groupValues?.get(1)?.takeIf { it == own } ?: return r
            uninstall(pkg)
            return push().let { it.copy(out = it.out + "\n(reinstalled: the signing key changed, so the old copy and its data were removed)") }
        }
        return r
    }

    suspend fun uninstall(pkg: String): ExecResult { guard(pkg); return warden.exec(listOf("pm", "uninstall", pkg)) }

    /** The user's hide_error_dialogs value before Kiln hid crash dialogs for a test run (null = not hidden by Kiln). */
    private var dialogsBefore: String? = null

    /**
     * While an app is tested on the hidden display its crashes must not pop "keeps stopping" over the
     * user's screen (it also blocks the next test): hide crash/ANR dialogs, remembering the user's setting.
     */
    // Hide and restore take turns: interleaved (Preview opened and closed at once), a late "1" landed after the
    // restore with no record kept, and the user's setting was lost for good.
    private val dialogsLock = kotlinx.coroutines.sync.Mutex()

    internal suspend fun hideCrashDialogs(): Unit = dialogsLock.withLock { hideLocked() }

    private suspend fun hideLocked() {
        if (testDisplay == null || dialogsBefore != null) return
        // A value saved by an earlier run that wasn't restored yet (Kiln restarted) is the user's: reading the
        // setting now would record Kiln's own "1" instead.
        // A failed read isn't the user's value: recording "null" would later delete their own setting. Then don't hide.
        dialogsBefore = dialogsFile?.takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null }
            ?: warden.exec(listOf("settings", "get", "global", "hide_error_dialogs")).takeIf { it.ok }?.out?.trim()?.ifBlank { "null" }
            ?: return
        dialogsFile?.writeText(dialogsBefore!!)
        warden.exec(listOf("settings", "put", "global", "hide_error_dialogs", "1"))
    }

    /** Put the user's crash-dialog setting back (when a run ends). The saved value is kept until that works. */
    suspend fun restoreCrashDialogs(): Unit = dialogsLock.withLock { restoreLocked() }

    private suspend fun restoreLocked() {
        val before = dialogsBefore ?: dialogsFile?.takeIf { it.isFile }?.readText()?.trim() ?: return
        val r = if (before == "null") warden.exec(listOf("settings", "delete", "global", "hide_error_dialogs"))
            else warden.exec(listOf("settings", "put", "global", "hide_error_dialogs", before))
        if (!r.ok) return          // Warden not ready (e.g. right after a reboot): try again later, don't lose it
        dialogsBefore = null
        dialogsFile?.delete()
    }

    /** Launch the app's launcher activity and wait for it to draw. */
    suspend fun launch(pkg: String): ExecResult {
        guard(pkg)
        runCatching { hideCrashDialogs() }
        val resolve = warden.exec(listOf("cmd", "package", "resolve-activity", "--brief",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", pkg))
        if (!resolve.ok) return resolve   // Warden's error, not "no launcher activity"
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
    suspend fun logMarker(): String {
        // To the millisecond: lines from the run that `install -r` killed in the same second must not count.
        val precise = warden.exec(listOf("date", "+%m-%d %H:%M:%S.%3N")).out.trim()
        return if (Regex("""\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}""").matches(precise)) precise
            else warden.exec(listOf("date", "+%m-%d %H:%M:%S.000")).out.trim()
    }

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
            // App not running: its own lines plus crash lines (the exception text doesn't name the package).
            if (pid != null) all else {
                val own = Regex("""${Regex.escape(pkg)}(?![\w.])""")
                all.filter { own.containsMatchIn(it) || "KILN-APP" in it || "KILN-CRASH" in it || "AndroidRuntime" in it }
            }
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
        // The newest FATAL EXCEPTION that is this app's: another app may have crashed after it, and
        // "kiln.app.foo" must not match "kiln.app.foobar".
        val owner = Regex("""Process: ${Regex.escape(pkg)}(,|\s|$)""")
        for (start in lines.indices.reversed()) {
            if ("FATAL EXCEPTION" !in lines[start]) continue
            val block = lines.drop(start).takeWhile { "AndroidRuntime" in it }.take(60)
            if (block.any { owner.containsMatchIn(it) }) return block.joinToString("\n") { it.substringAfter("AndroidRuntime: ") }
        }
        return null
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
        // The hidden display: the app reports its own tree (the kit's inspector, in every
        // development build). uiautomator only sees the focused display, and focusing this one
        // would take input focus from the user's screen. Retry briefly while a just-launched
        // activity resumes.
        if (testDisplay != null) {
            val pkg = expect ?: foregroundPackage() ?: return emptyList()
            for (attempt in 0 until 6) {
                inspect(pkg)?.let { xml -> parseTree(xml).takeIf { it.isNotEmpty() }?.let { return it } }
                kotlinx.coroutines.delay(300)
            }
            return emptyList()
        }
        // The user's screen: uiautomator. Remove the previous dump first so a failed dump can't
        // return the last screen's tree; retry briefly while the screen settles.
        val path = "/data/local/tmp/kiln-ui.xml"
        var xml = ""
        for (attempt in 0 until 6) {
            val r = warden.exec(listOf("sh", "-c", "rm -f $path; uiautomator dump $path >/dev/null 2>&1; cat $path 2>/dev/null"), timeoutMs = 30_000)
            xml = r.out.substring(r.out.indexOf('<').coerceAtLeast(0))
            if (xml.startsWith("<") && "<node" in xml && (expect == null || "package=\"$expect\"" in xml ||
                    Regex("package=\"([^\"]+)\"").find(xml)?.groupValues?.get(1) in SYSTEM_DIALOGS)) break   // a dialog is a valid answer
            kotlinx.coroutines.delay(300)
        }
        // Never another window's tree for an app we expected (Kiln's own chat after a BACK or crash): "nothing" instead.
        // Except a system window the app opened over itself (permission prompt, photo picker, share sheet): that is
        // what the user sees, and the agent must see it to answer it.
        if (expect != null && "package=\"$expect\"" !in xml) {
            val shown = Regex("""package="([^"]+)"""").find(xml)?.groupValues?.get(1)
            // Only a dialog that is the foreground activity (not the shade, lock screen or a crash dialog layered over).
            return if (shown in SYSTEM_DIALOGS && foregroundPackage() == shown) parseTree(xml) else emptyList()
        }
        return parseTree(xml)
    }

    /** The app's own semantics tree via the kit's inspector, or null when the app isn't running. */
    private suspend fun inspect(pkg: String): String? {
        val r = warden.exec(listOf("am", "broadcast", "-a", "app.kiln.kit.UI_TREE", "-p", pkg), timeoutMs = 10_000)
        if (!r.out.contains("result=1")) return null
        val data = r.out.substringAfter("data=\"", "").substringBefore("\"")
        return runCatching { String(java.util.Base64.getDecoder().decode(data)) }.getOrNull()?.takeIf { "<hierarchy" in it }
    }

    private fun parseTree(xml: String): List<UiNode> {
        if (!xml.startsWith("<")) return emptyList()
        // Truncated or malformed XML (an app dying mid-dump) reads as an empty screen, not a crashed tool.
        val doc = runCatching { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream()) }.getOrNull() ?: return emptyList()
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
                left = l.toInt(), top = t.toInt(), right = rr.toInt(), bottom = b.toInt(), focused = e.getAttribute("focused").takeIf { it.isNotEmpty() }?.let { it == "true" },
            )
            if (n.text.isNotBlank() || n.desc.isNotBlank() || n.clickable || n.scrollable || n.checked != null || n.focused == true || "EditText" in n.cls) out += n
        }
        return out
    }

    /** Find a node by visible text / content description / resource id (case-insensitive contains). */
    fun find(nodes: List<UiNode>, target: String): UiNode? = findNode(nodes, target)

    suspend fun tap(x: Int, y: Int) =
        if (testDisplay != null) inApp("--es", "op", "tap", "--ei", "x", "$x", "--ei", "y", "$y")
        else warden.exec(input("tap", "$x", "$y"))
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int = 300) =
        if (testDisplay != null) inApp("--es", "op", "swipe", "--ei", "x", "$x1", "--ei", "y", "$y1", "--ei", "x2", "$x2", "--ei", "y2", "$y2", "--ei", "ms", "$ms")
        else warden.exec(input("swipe", "$x1", "$y1", "$x2", "$y2", "$ms"))
    suspend fun key(key: String) = key.uppercase().let { if (it.startsWith("KEYCODE_")) it else "KEYCODE_$it" }.let { k ->
        if (testDisplay != null) inApp("--es", "op", "key", "--es", "key", k) else warden.exec(input("keyevent", k))
    }
    /** `input text` needs spaces as %s; in the app, text goes into the focused field as is. */
    suspend fun type(text: String, replace: Boolean = false) =
        // Replacing needs the in-app path (system `input text` only appends), on either screen.
        if (testDisplay != null || replace) inApp("--es", "op", "text", "--es", "text", text, "--ez", "replace", replace.toString())
        else warden.exec(input("text", text.replace(" ", "%s")))

    /**
     * Input for the app on the hidden display, performed inside it by the kit's inspector.
     * Input injected through the system (`input -d`) moves Android's focus to that display, so
     * the user's own typing in Kiln would go to the app under test — and key events then queued
     * for a window without focus make Android report Kiln as not responding.
     */
    private suspend fun inApp(vararg extras: String): ExecResult {
        val pkg = foregroundPackage() ?: return ExecResult(1, "", "can't tell which app is showing", 0)
        // Only Kiln's own apps get the input (the agent's text, maybe a test password): not whatever the user switched to.
        if (!pkg.startsWith("kiln.app.")) return ExecResult(1, "", "$pkg is in front, not the app under test — launch it first", 0)
        val r = warden.exec(listOf("am", "broadcast", "-a", "app.kiln.kit.INPUT", "-p", pkg) + extras, timeoutMs = 15_000)
        val data = r.out.substringAfter("data=\"", "").substringBefore("\"")
        return when {
            r.out.contains("result=1") -> ExecResult(0, data, "", r.ms)
            r.out.contains("result=2") -> ExecResult(1, "", data.removePrefix("error: "), r.ms)
            else -> ExecResult(1, "", "$pkg didn't answer — rebuild it so it has the current kit", r.ms)
        }
    }
    // Warden passes argv without a shell, so no escaping: backslashes would be typed literally.
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

    /** Screen density in dpi (the test display's, or the phone's). */
    suspend fun density(): Int {
        testDisplay?.let { it.id(); return it.densityDpi }
        return Regex("""(\d+)""").findAll(warden.exec(listOf("wm", "density")).out).lastOrNull()?.value?.toIntOrNull() ?: 420
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
/** Icon-like targets and the words their buttons are labelled with. */
private val SYMBOLS = mapOf(
    "+" to listOf("add", "new", "create"), "＋" to listOf("add", "new", "create"),
    "×" to listOf("close", "clear", "dismiss"), "✕" to listOf("close", "clear", "dismiss"), "x" to listOf("close", "clear", "dismiss"),
    "←" to listOf("back", "navigate up"), "<" to listOf("back", "navigate up"),
    "⋮" to listOf("more", "options", "menu"), "..." to listOf("more", "options", "menu"), "☰" to listOf("menu", "navigation"),
    "✓" to listOf("done", "save", "confirm"), "✔" to listOf("done", "save", "confirm"),
    "🔍" to listOf("search"), "✏" to listOf("edit"), "✎" to listOf("edit"), "🗑" to listOf("delete", "remove"),
)

fun findNode(nodes: List<UiNode>, target: String): UiNode? {
    // A symbol names a button by its icon (run 10: "+" matched the text "Tap + to add…", not the + button).
    SYMBOLS[target.trim()]?.let { words ->
        // Whole words: "+" must find "Add expense", not "Renew" or "Edit address".
        nodes.filter { it.clickable }.firstOrNull { n -> words.any { w -> Regex("""\b${Regex.escape(w)}\b""").containsMatchIn("${n.desc} ${n.text}".lowercase()) } }?.let { return it }
    }
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

/** System windows an app opens over itself (permission prompts, pickers, share sheets): shown to the agent as its screen. */
internal val SYSTEM_DIALOGS = setOf(
    // Not systemui or "android": the notification shade, lock screen and crash dialogs aren't the app's screen.
    "com.android.permissioncontroller", "com.google.android.permissioncontroller",
    "com.android.documentsui", "com.google.android.documentsui", "com.android.intentresolver",
    "com.google.android.providers.media.module", "com.android.providers.media.module", "com.android.camera2",
)
