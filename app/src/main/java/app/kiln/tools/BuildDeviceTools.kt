package app.kiln.tools

import app.kiln.build.BuildEngine
import app.kiln.build.BuildResult
import app.kiln.build.ProjectMeta
import app.kiln.core.KJ
import app.kiln.core.KJPretty
import app.kiln.core.a
import app.kiln.core.int
import app.kiln.core.str
import app.kiln.device.Device
import app.kiln.device.UiNode
import app.kiln.device.Warden
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

private fun JsonObject.req(k: String) = str(k) ?: throw IllegalArgumentException("missing '$k'")

/** Diagnostics as compact text: one per line, errors first, with the offending source line. */
fun BuildResult.report(maxItems: Int = 40): String = buildString {
    appendLine(if (ok) "BUILD OK in ${totalMs} ms" else "BUILD FAILED (${errors.size} error${if (errors.size == 1) "" else "s"})")
    appendLine("steps: " + steps.joinToString(", ") { "${it.step}${if (it.skipped) " (cached)" else " ${it.ms}ms"}" })
    (errors + warnings).take(maxItems).forEach { d ->
        val where = listOfNotNull(d.file, d.line?.toString(), d.col?.toString()).joinToString(":")
        appendLine("${d.severity.uppercase()} [${d.tool}] ${if (where.isNotBlank()) "$where " else ""}${d.message}")
        d.source?.let { appendLine("    > $it") }
    }
    if (errors.size + warnings.size > maxItems) appendLine("… ${errors.size + warnings.size - maxItems} more")
}

class ProjectInfoTool : Tool {
    override val name = "project_info"
    override val description = "Show the project's kiln.json (package, label, versions, permissions) and its source tree."
    override val schema = schema { }
    override val traits = setOf(Trait.READ_ONLY, Trait.PARALLEL_SAFE)
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val p = ctx.project
        return ToolResult.ok(KJPretty.encodeToString(ProjectMeta.serializer(), p.meta()) + "\n\nfiles:\n" +
            p.files().joinToString("\n") { "  " + p.rel(it) })
    }
}

class SetAppMetaTool : Tool {
    override val name = "set_app_meta"
    override val description = "Change the app's label, version or runtime permissions (kiln.json). Permissions are names like CAMERA or android.permission.CAMERA. The package name is fixed."
    override val schema = schema {
        str("label", "App name shown on the launcher; empty to keep.")
        str("version_name", "e.g. 1.1; empty to keep.")
        strList("permissions", "The complete permission list (replaces the current one).")
    }
    override val traits = emptySet<Trait>()
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val m = ctx.project.meta()
        val next = m.copy(
            label = input.str("label")?.takeIf { it.isNotBlank() } ?: m.label,
            versionName = input.str("version_name")?.takeIf { it.isNotBlank() } ?: m.versionName,
            versionCode = m.versionCode + 1,
            permissions = input.a("permissions")?.map { (it as JsonPrimitive).content } ?: m.permissions,
        )
        ctx.project.metaFile.writeText(KJPretty.encodeToString(ProjectMeta.serializer(), next))
        // The manifest label follows kiln.json.
        val man = ctx.project.manifest
        man.writeText(man.readText().replace(Regex("""android:label="[^"]*""""), "android:label=\"${next.label.replace("\"", "&quot;")}\""))
        return ToolResult.ok("kiln.json updated: label=${next.label}, version=${next.versionName} (${next.versionCode}), permissions=${next.permissions}")
    }
}

class CheckTool(private val builds: BuildEngine) : Tool {
    override val name = "check"
    override val description = "Compile only (resources + Kotlin), no packaging — the fast way to see errors after an edit. Returns structured diagnostics: SEVERITY [tool] file:line:col message, then the source line."
    override val schema = schema { }
    override val traits = setOf(Trait.READ_ONLY, Trait.LONG_RUNNING)
    override val timeoutMs = 600_000L
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val r = builds.build(ctx.project, checkOnly = true) { ctx.progress(it) }
        ctx.state.lastBuild = r
        return ToolResult(r.report(), isError = !r.ok, summary = if (r.ok) "check OK" else "${r.errors.size} errors")
    }
}

class BuildTool(private val builds: BuildEngine) : Tool {
    override val name = "build"
    override val description = "Build the signed APK. Unchanged steps are cached. Returns diagnostics on failure; on success, the APK path and timings."
    override val schema = schema { }
    override val traits = setOf(Trait.LONG_RUNNING)
    override val timeoutMs = 900_000L
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val r = builds.build(ctx.project) { ctx.progress(it) }
        ctx.state.lastBuild = r
        return ToolResult(r.report() + (r.apk?.let { "apk: ${File(it).name} (${File(it).length() / 1024} KB)" } ?: ""),
            isError = !r.ok, summary = if (r.ok) "build OK ${r.totalMs}ms" else "${r.errors.size} errors")
    }
}

class CleanTool(private val builds: BuildEngine) : Tool {
    override val name = "clean"
    override val description = "Delete build outputs so the next build runs every step."
    override val schema = schema { }
    override val traits = emptySet<Trait>()
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult { builds.clean(ctx.project); return ToolResult.ok("cleaned") }
}

/** Shared base for device tools: Warden must be ready. */
abstract class DeviceTool(protected val warden: Warden, protected val device: Device) : Tool {
    override val traits: Set<Trait> get() = setOf(Trait.NEEDS_BROKER)
    protected fun pkg(ctx: ToolContext) = ctx.project.meta().`package`
    final override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        if (warden.status() != Warden.Status.READY)
            return ToolResult.error("Warden is ${warden.status().name.lowercase().replace('_', ' ')} — device tools need it (start Warden and grant Kiln).")
        return exec(ctx, input)
    }
    abstract suspend fun exec(ctx: ToolContext, input: JsonObject): ToolResult

    protected fun treeText(nodes: List<UiNode>): String = nodes.joinToString("\n") { n ->
        val flags = listOfNotNull(if (n.clickable) "click" else null, if (n.scrollable) "scroll" else null,
            n.checked?.let { if (it) "checked" else "unchecked" }, if (!n.enabled) "disabled" else null).joinToString(",")
        "[${n.cx},${n.cy}] ${n.cls.substringAfterLast('.')} \"${n.label()}\"${if (flags.isNotEmpty()) " ($flags)" else ""}"
    }
}

class InstallTool(w: Warden, d: Device) : DeviceTool(w, d) {
    override val name = "install"
    override val description = "Install the last successful build on this phone (silently, through Warden)."
    override val schema = schema { }
    override suspend fun exec(ctx: ToolContext, input: JsonObject): ToolResult {
        val apk = ctx.state.lastBuild?.apk?.let(::File)?.takeIf { it.isFile } ?: return ToolResult.error("no APK yet — run build first")
        val r = device.install(apk)
        return if (r.out.contains("Success")) ToolResult.ok("installed ${pkg(ctx)}") else ToolResult.error("install failed: ${r.all.trim()}")
    }
}

class RunAppTool(private val builds: BuildEngine, w: Warden, d: Device) : DeviceTool(w, d) {
    override val name = "run_app"
    override val description = "The verify loop in one call: build → install → launch → wait → report crash (if any), new error/warning log lines, a screenshot and the on-screen UI tree. Use after every meaningful change."
    override val schema = schema { int("wait_ms", "How long to let the app run before checking (1000–10000).") }
    override val traits = setOf(Trait.NEEDS_BROKER, Trait.LONG_RUNNING, Trait.RETURNS_IMAGE)
    override val timeoutMs = 900_000L
    override suspend fun exec(ctx: ToolContext, input: JsonObject): ToolResult {
        val r = builds.build(ctx.project) { ctx.progress(it) }
        ctx.state.lastBuild = r
        if (!r.ok) return ToolResult(r.report(), isError = true, summary = "${r.errors.size} build errors")
        val pkg = pkg(ctx)
        ctx.progress("install")
        val inst = device.install(File(r.apk!!))
        if (!inst.out.contains("Success")) return ToolResult.error("install failed: ${inst.all.trim()}")
        val marker = device.logMarker()
        ctx.progress("launch")
        device.launch(pkg)
        delay((input.int("wait_ms") ?: 2500).toLong().coerceIn(1000, 10_000))
        ctx.state.logMarkers[pkg] = marker
        val crash = device.lastCrash(pkg, marker)
        val logs = device.logcat(pkg, marker, minLevel = "W", max = 60)
        val alive = device.pid(pkg) != null
        val shot = if (alive) device.screenshot() else null
        val tree = if (alive) device.uiTree() else emptyList()
        val text = buildString {
            appendLine("build OK ${r.totalMs}ms · installed · ${if (alive) "running" else "NOT RUNNING"}")
            if (crash != null) { appendLine("CRASH:"); appendLine(crash) }
            if (logs.isNotBlank()) { appendLine("log (W and above since launch):"); appendLine(logs.lines().takeLast(40).joinToString("\n")) }
            if (tree.isNotEmpty()) { appendLine("screen:"); appendLine(treeText(tree).lines().take(80).joinToString("\n")) }
        }
        return ToolResult(ctx.spill(text), listOfNotNull(shot), isError = crash != null || !alive,
            summary = if (crash != null) "crashed" else if (alive) "running" else "not running")
    }
}

class LaunchTool(w: Warden, d: Device) : DeviceTool(w, d) {
    override val name = "launch"
    override val description = "(Re)start the installed app and wait for its first frame."
    override val schema = schema { }
    override suspend fun exec(ctx: ToolContext, input: JsonObject): ToolResult {
        val pkg = pkg(ctx)
        ctx.state.logMarkers[pkg] = device.logMarker()
        val r = device.launch(pkg)
        return if (r.ok) ToolResult.ok(r.out.lines().filter { it.startsWith("Status") || it.startsWith("TotalTime") }.joinToString("; ").ifBlank { "launched" })
               else ToolResult.error(r.all.trim())
    }
}

class StopAppTool(w: Warden, d: Device) : DeviceTool(w, d) {
    override val name = "stop_app"
    override val description = "Force-stop the app."
    override val schema = schema { }
    override suspend fun exec(ctx: ToolContext, input: JsonObject) = device.stop(pkg(ctx)).let { ToolResult.ok("stopped") }
}

class ClearDataTool(w: Warden, d: Device) : DeviceTool(w, d) {
    override val name = "clear_data"
    override val description = "Erase the app's data (fresh-install state)."
    override val schema = schema { }
    override val traits = setOf(Trait.NEEDS_BROKER, Trait.DESTRUCTIVE)
    override suspend fun exec(ctx: ToolContext, input: JsonObject) = device.clearData(pkg(ctx)).let { ToolResult.ok(it.out.trim().ifBlank { "cleared" }) }
}

class GrantPermissionTool(w: Warden, d: Device) : DeviceTool(w, d) {
    override val name = "grant_permission"
    override val description = "Grant a runtime permission to the app (e.g. POST_NOTIFICATIONS, CAMERA) to test flows without tapping the dialog."
    override val schema = schema { str("permission", "Permission name.") }
    override suspend fun exec(ctx: ToolContext, input: JsonObject): ToolResult {
        val r = device.grant(pkg(ctx), input.req("permission"))
        return if (r.ok) ToolResult.ok("granted") else ToolResult.error(r.all.trim())
    }
}

class LogcatTool(w: Warden, d: Device) : DeviceTool(w, d) {
    override val name = "logcat"
    override val description = "App log lines. since_last=true returns only lines since the previous logcat/launch (the usual case). level: V D I W E."
    override val schema = schema {
        bool("since_last", "Only new lines since the last check.")
        str("level", "Minimum level.", enum = listOf("V", "D", "I", "W", "E"))
        str("grep", "Only lines matching this regex; empty for all.")
    }
    override val traits = setOf(Trait.NEEDS_BROKER, Trait.READ_ONLY)
    override suspend fun exec(ctx: ToolContext, input: JsonObject): ToolResult {
        val pkg = pkg(ctx)
        val since = if (input["since_last"]?.toString() == "true") ctx.state.logMarkers[pkg] else null
        val next = device.logMarker()
        var text = device.logcat(pkg, since, input.str("level") ?: "V", max = 2000)
        input.str("grep")?.takeIf { it.isNotBlank() }?.let { g -> val re = Regex(g); text = text.lines().filter { re.containsMatchIn(it) }.joinToString("\n") }
        ctx.state.logMarkers[pkg] = next
        return ToolResult.ok(ctx.spill(text.ifBlank { "(no lines)" }), "${text.lines().count { it.isNotBlank() }} lines")
    }
}

class LastCrashTool(w: Warden, d: Device) : DeviceTool(w, d) {
    override val name = "last_crash"
    override val description = "The app's most recent crash: exception, root cause, and stack frames with the app's own frames first."
    override val schema = schema { }
    override val traits = setOf(Trait.NEEDS_BROKER, Trait.READ_ONLY)
    override suspend fun exec(ctx: ToolContext, input: JsonObject) =
        device.lastCrash(pkg(ctx), null)?.let { ToolResult.ok(it, "crash found") } ?: ToolResult.ok("no crash recorded")
}

class ScreenshotTool(w: Warden, d: Device) : DeviceTool(w, d) {
    override val name = "screenshot"
    override val description = "A screenshot of the phone's screen right now (PNG)."
    override val schema = schema { }
    override val traits = setOf(Trait.NEEDS_BROKER, Trait.READ_ONLY, Trait.RETURNS_IMAGE)
    override suspend fun exec(ctx: ToolContext, input: JsonObject): ToolResult {
        val png = device.screenshot() ?: return ToolResult.error("screenshot failed")
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size, o)
        val (w, h) = device.screenSize()
        // The image is scaled down; tap/ui_tree use screen pixels. Say so, or models tap in image space.
        val k = if (o.outWidth > 0) w.toDouble() / o.outWidth else 1.0
        return ToolResult("screenshot ${o.outWidth}x${o.outHeight} of a ${w}x$h screen. Tap by target text; " +
            "if you must use x,y, they are screen pixels = image pixels × ${"%.2f".format(k)}.", listOf(png))
    }
}

class UiTreeTool(w: Warden, d: Device) : DeviceTool(w, d) {
    override val name = "ui_tree"
    override val description = "What is on screen as text: each element's centre [x,y], type, label and flags (click/scroll/checked). Cheaper than a screenshot for finding things to tap."
    override val schema = schema { }
    override val traits = setOf(Trait.NEEDS_BROKER, Trait.READ_ONLY)
    override suspend fun exec(ctx: ToolContext, input: JsonObject): ToolResult {
        val nodes = device.uiTree()
        return ToolResult.ok(ctx.spill(treeText(nodes).ifBlank { "(empty screen)" }), plural(nodes.size, "element"))
    }
}

class TapTool(w: Warden, d: Device) : DeviceTool(w, d) {
    override val name = "tap"
    override val description = "Tap an element by its visible text, content description or resource id (preferred — copy the label " +
        "from ui_tree, e.g. Add 250ml), or at x,y in screen pixels (ui_tree coordinates, not screenshot pixels)."
    override val schema = schema {
        str("target", "Label to tap, as ui_tree shows it; empty to use x,y.")
        int("x", "Screen-pixel X when target is empty (0 otherwise).")
        int("y", "Screen-pixel Y when target is empty (0 otherwise).")
    }
    override suspend fun exec(ctx: ToolContext, input: JsonObject): ToolResult {
        // "640, 1427" in target is a coordinate, not a label.
        val coord = Regex("""^\s*\[?(\d+)\s*,\s*(\d+)]?\s*$""").find(input.str("target") ?: "")
        val target = input.str("target")?.takeIf { it.isNotBlank() && coord == null }
        val (x, y) = if (target != null) {
            val nodes = device.uiTree()
            val n = device.find(nodes, target) ?: return ToolResult.error("no element matching \"$target\". On screen now:\n" +
                treeText(nodes.filter { it.clickable || it.text.isNotBlank() || it.desc.isNotBlank() }).take(2500))
            n.cx to n.cy
        } else if (coord != null) coord.groupValues[1].toInt() to coord.groupValues[2].toInt()
        else (input.int("x") ?: 0) to (input.int("y") ?: 0)
        device.tap(x, y)
        delay(600)
        return ToolResult.ok("tapped ${target?.let { "\"$it\" at $x,$y" } ?: "$x,$y"}")
    }
}

class TypeTool(w: Warden, d: Device) : DeviceTool(w, d) {
    override val name = "type_text"
    override val description = "Type text into the focused field (tap the field first)."
    override val schema = schema { str("text", "Text to type.") }
    override suspend fun exec(ctx: ToolContext, input: JsonObject) = device.type(input.req("text")).let { ToolResult.ok("typed") }
}

class SwipeTool(w: Warden, d: Device) : DeviceTool(w, d) {
    override val name = "swipe"
    override val description = "Swipe/scroll the screen: direction up | down | left | right (content moves the other way, like a finger)."
    override val schema = schema { str("direction", "Finger direction.", enum = listOf("up", "down", "left", "right")) }
    override suspend fun exec(ctx: ToolContext, input: JsonObject): ToolResult {
        val (w, h) = device.screenSize()
        val (x1, y1, x2, y2) = when (input.req("direction")) {
            "up" -> listOf(w / 2, h * 3 / 4, w / 2, h / 4); "down" -> listOf(w / 2, h / 4, w / 2, h * 3 / 4)
            "left" -> listOf(w * 4 / 5, h / 2, w / 5, h / 2); else -> listOf(w / 5, h / 2, w * 4 / 5, h / 2)
        }
        device.swipe(x1, y1, x2, y2); delay(500)
        return ToolResult.ok("swiped ${input.str("direction")}")
    }
}

class KeyTool(w: Warden, d: Device) : DeviceTool(w, d) {
    override val name = "press_key"
    override val description = "Press a key: BACK, HOME, ENTER, TAB, DEL, APP_SWITCH."
    override val schema = schema { str("key", "Key name.", enum = listOf("BACK", "HOME", "ENTER", "TAB", "DEL", "APP_SWITCH")) }
    override suspend fun exec(ctx: ToolContext, input: JsonObject) = device.key(input.req("key")).let { delay(400); ToolResult.ok("pressed ${input.str("key")}") }
}

class WaitForTool(w: Warden, d: Device) : DeviceTool(w, d) {
    override val name = "wait_for"
    override val description = "Wait until text (or a description/id) appears on screen, up to timeout_ms. Returns whether it appeared."
    override val schema = schema { str("target", "Text to wait for."); int("timeout_ms", "Max wait (500–20000).") }
    override suspend fun exec(ctx: ToolContext, input: JsonObject): ToolResult {
        val end = System.currentTimeMillis() + (input.int("timeout_ms") ?: 5000).coerceIn(500, 20_000)
        while (System.currentTimeMillis() < end) {
            if (device.find(device.uiTree(), input.req("target")) != null) return ToolResult.ok("\"${input.str("target")}\" is on screen")
            delay(500)
        }
        return ToolResult.error("\"${input.str("target")}\" did not appear")
    }
}

class DumpsysTool(w: Warden, d: Device) : DeviceTool(w, d) {
    private val allowed = listOf("activity", "meminfo", "gfxinfo", "package", "notification", "jobscheduler", "alarm", "power", "battery")
    override val name = "dumpsys"
    override val description = "System state for the app via dumpsys: ${allowed.joinToString()} (activity/meminfo/gfxinfo/package are filtered to the app)."
    override val schema = schema { str("service", "Service.", enum = allowed) }
    override val traits = setOf(Trait.NEEDS_BROKER, Trait.READ_ONLY)
    override suspend fun exec(ctx: ToolContext, input: JsonObject): ToolResult {
        val svc = input.req("service"); val pkg = pkg(ctx)
        val args = when (svc) { "meminfo", "gfxinfo", "package" -> listOf("dumpsys", svc, pkg)
            "activity" -> listOf("dumpsys", "activity", "activities"); else -> listOf("dumpsys", svc) }
        var out = warden.exec(args, timeoutMs = 30_000).out
        if (svc in setOf("notification", "jobscheduler", "alarm", "activity")) out = out.lines().filter { pkg in it }.joinToString("\n")
        return ToolResult.ok(ctx.spill(out.ifBlank { "(nothing for $pkg)" }))
    }
}

class ShellTool(w: Warden, d: Device) : DeviceTool(w, d) {
    override val name = "shell"
    override val description = "Run a shell command as the shell user (through Warden). Last resort — prefer the dedicated tools. Needs approval."
    override val schema = schema { str("command", "Command line for sh -c.") }
    override val traits = setOf(Trait.NEEDS_BROKER, Trait.NEEDS_APPROVAL, Trait.DESTRUCTIVE)
    override suspend fun exec(ctx: ToolContext, input: JsonObject): ToolResult {
        val r = warden.exec(listOf("sh", "-c", input.req("command")), timeoutMs = 60_000)
        return ToolResult(ctx.spill("exit ${r.code}\n${r.all}"), isError = !r.ok)
    }
}
