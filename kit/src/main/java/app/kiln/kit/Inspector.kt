package app.kiln.kit

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.core.content.ContextCompat

/**
 * Lets Kiln read the app's UI while it runs on Kiln's hidden test display.
 *
 * Android's accessibility (what `uiautomator dump` uses) only sees the focused display, and
 * focusing the hidden one would take input focus from the user's screen. So a development build
 * answers `am broadcast -a app.kiln.kit.UI_TREE -p <package>` itself: the resumed activity's
 * Compose semantics as uiautomator-style XML (base64 in the broadcast result). Release builds
 * never register it.
 */
internal object KilnInspector {
    const val ACTION = "app.kiln.kit.UI_TREE"
    /** Input for the app under test, performed inside it: see [input]. */
    const val INPUT = "app.kiln.kit.INPUT"
    @Volatile private var installed = false
    @Volatile private var resumed: Activity? = null
    /**
     * Every Compose root of the app, bottom window first: the activity's, then each dialog, bottom
     * sheet, dropdown or popup (Compose draws those in windows of their own, outside the activity's views).
     */
    private fun liveRoots(): List<ViewRootForTest> =
        android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.isShown }
            .flatMap { roots(it) }.filterIsInstance<ViewRootForTest>()

    fun install(activity: Activity) {
        val app = activity.application
        if (installed || (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        installed = true
        resumed = activity
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { resumed = a }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) { if (resumed === a) resumed = null }
        })
        // Exported so the shell (Warden) can ask — but only senders holding DUMP (the shell; no ordinary app) may: it reads
        // this app's screen and can tap and type in it.
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == INPUT) {
                    val r = runCatching { input(i) }.getOrElse { "error: ${it.message}" }
                    resultCode = if (r.startsWith("error")) 2 else 1
                    resultData = r
                    return
                }
                val xml = runCatching { dump() }.getOrElse { "<error>${it.message}</error>" }
                resultCode = 1
                resultData = Base64.encodeToString(xml.toByteArray(), Base64.NO_WRAP)
            }
        }, IntentFilter().apply { addAction(ACTION); addAction(INPUT) }, "android.permission.DUMP", null, ContextCompat.RECEIVER_EXPORTED)
    }

    private fun dump(): String {
        val a = resumed ?: return "<hierarchy rotation=\"0\"></hierarchy>"
        val out = StringBuilder("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">")
        val roots = liveRoots().ifEmpty { roots(a.window.decorView).filterIsInstance<ViewRootForTest>() }
        // A modal dialog or sheet covers what's under it: list only what the user can reach.
        val top = roots.lastOrNull()
        val shown = if (top != null && roots.size > 1 && covers(top.view, a.window.decorView)) listOf(top) else roots
        shown.forEach { r ->
            val at = IntArray(2).also { r.view.getLocationOnScreen(it) }
            node(r.semanticsOwner.rootSemanticsNode, a.packageName, at, out)
        }
        return out.append("</hierarchy>").toString()
    }

    /**
     * Whether [v]'s window is modal over [base]'s: a dialog or bottom sheet (an application window of
     * its own), as opposed to a dropdown or popup (a panel attached to the screen beneath).
     */
    private fun covers(v: View, base: View): Boolean {
        val win = v.rootView
        if (win === base.rootView) return false
        val type = (win.layoutParams as? android.view.WindowManager.LayoutParams)?.type ?: return false
        return type == android.view.WindowManager.LayoutParams.TYPE_APPLICATION ||
            (win.width >= base.width * 0.9 && win.height >= base.height * 0.9)
    }

    /** The topmost Compose window under the screen point (x, y), or the activity's decor view. */
    private fun targetAt(x: Float, y: Float, fallback: View): View {
        for (r in liveRoots().asReversed()) {
            val win = r.view.rootView
            val at = IntArray(2).also { win.getLocationOnScreen(it) }
            if (x >= at[0] && y >= at[1] && x < at[0] + win.width && y < at[1] + win.height) return win
        }
        return fallback
    }

    /**
     * Taps, swipes, typing and keys delivered inside the app. Input injected through the system
     * moves Android's focus to the hidden display, and the user's typing in Kiln would follow it;
     * events dispatched to the app's own window don't.
     */
    private fun input(i: Intent): String {
        val a = resumed ?: return "error: no activity on screen"
        val decor = a.window.decorView
        fun touch(points: List<Pair<Float, Float>>, totalMs: Long) {
            // The whole gesture goes to the window under its first point (a dialog sits above the activity).
            val root = targetAt(points.first().first, points.first().second, decor)
            val at = IntArray(2).also { root.getLocationOnScreen(it) }
            val t0 = android.os.SystemClock.uptimeMillis()
            val step = if (points.size > 1) totalMs / (points.size - 1) else 0L
            points.forEachIndexed { k, (x, y) ->
                val action = when (k) { 0 -> android.view.MotionEvent.ACTION_DOWN; points.lastIndex -> android.view.MotionEvent.ACTION_UP
                    else -> android.view.MotionEvent.ACTION_MOVE }
                val e = android.view.MotionEvent.obtain(t0, t0 + k * step, action, x - at[0], y - at[1], 0)
                e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                root.dispatchTouchEvent(e); e.recycle()
            }
        }
        val x = i.getIntExtra("x", 0).toFloat(); val y = i.getIntExtra("y", 0).toFloat()
        return when (i.getStringExtra("op")) {
            "tap" -> { touch(listOf(x to y, x to y), 60); "tapped" }
            "swipe" -> {
                val x2 = i.getIntExtra("x2", 0).toFloat(); val y2 = i.getIntExtra("y2", 0).toFloat()
                touch((0..12).map { k -> (x + (x2 - x) * k / 12) to (y + (y2 - y) * k / 12) }, i.getIntExtra("ms", 300).toLong())
                "swiped"
            }
            "text" -> {
                val text = i.getStringExtra("text").orEmpty()
                val field = (liveRoots().ifEmpty { roots(decor).filterIsInstance<ViewRootForTest>() }).asReversed()
                    .flatMap { all(it.semanticsOwner.unmergedRootSemanticsNode) }
                    .firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true && SemanticsActions.SetText in it.config }
                    ?: return "error: no text field has focus — tap one first"
                val now = field.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
                val replace = i.getBooleanExtra("replace", false)
                // A password field exposes only its bullets: appending by rewriting the whole text put them in.
                val masked = field.config.contains(SemanticsProperties.Password)
                val result = if (replace) text else if (masked) "•".repeat(now.length) + text else now + text
                if (!replace && masked && SemanticsActions.InsertTextAtCursor in field.config)
                    field.config[SemanticsActions.InsertTextAtCursor].action?.invoke(androidx.compose.ui.text.AnnotatedString(text))
                else field.config[SemanticsActions.SetText].action?.invoke(androidx.compose.ui.text.AnnotatedString(if (replace) text else now + text))
                // Say what the field holds now: typing twice appends, and the agent should see that.
                val name = (field.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
                    ?: field.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text).orEmpty()
                "typed into ${name.ifBlank { "the field" }} — it now holds “$result”"
            }
            "key" -> {
                val key = i.getStringExtra("key").orEmpty().uppercase().removePrefix("KEYCODE_")
                val code = android.view.KeyEvent.keyCodeFromString("KEYCODE_$key")
                if (code == android.view.KeyEvent.KEYCODE_UNKNOWN) return "error: unknown key $key"
                // Keys go to the top window: Back closes an open dialog or sheet before leaving the screen.
                val root = liveRoots().lastOrNull()?.view?.rootView ?: decor
                if (key == "BACK" && root === decor.rootView) {
                    (a as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher?.onBackPressed() ?: a.finish(); return "back"
                }
                val t = android.os.SystemClock.uptimeMillis()
                root.dispatchKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, code, 0))
                root.dispatchKeyEvent(android.view.KeyEvent(t, t + 30, android.view.KeyEvent.ACTION_UP, code, 0))
                "pressed $key"
            }
            else -> "error: unknown op"
        }
    }

    private fun all(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { all(it) }

    private fun roots(v: View): List<View> = when {
        v is RootForTest -> listOf(v)
        v is ViewGroup -> (0 until v.childCount).flatMap { roots(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun node(n: SemanticsNode, pkg: String, at: IntArray, out: StringBuilder) {
        val c = n.config
        val text = (c.getOrNull(SemanticsProperties.EditableText)?.text
            ?: c.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }).orEmpty()
        val desc = c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
        val role = c.getOrNull(SemanticsProperties.Role)
        val toggle = c.getOrNull(SemanticsProperties.ToggleableState)
        val selected = c.getOrNull(SemanticsProperties.Selected)
        val cls = when {
            c.getOrNull(SemanticsProperties.EditableText) != null -> "android.widget.EditText"
            role == Role.Button -> "android.widget.Button"
            role == Role.Checkbox -> "android.widget.CheckBox"
            role == Role.Switch -> "android.widget.Switch"
            role == Role.RadioButton -> "android.widget.RadioButton"
            role == Role.Image -> "android.widget.ImageView"
            text.isNotEmpty() -> "android.widget.TextView"
            else -> "android.view.View"
        }
        val b = n.boundsInRoot
        out.append("<node text=\"").append(esc(text)).append("\" resource-id=\"").append(esc(c.getOrNull(SemanticsProperties.TestTag)))
            .append("\" class=\"").append(cls).append("\" package=\"").append(pkg)
            .append("\" content-desc=\"").append(esc(desc))
            // Chips, segments and tabs are "selected" rather than toggled: report both as checked.
            .append("\" checkable=\"").append(toggle != null || selected != null)
            .append("\" checked=\"").append(toggle == ToggleableState.On || selected == true)
            .append("\" focused=\"").append(c.getOrNull(SemanticsProperties.Focused) == true)
            .append("\" clickable=\"").append(SemanticsActions.OnClick in c)
            .append("\" enabled=\"").append(SemanticsProperties.Disabled !in c)
            .append("\" scrollable=\"").append(SemanticsActions.ScrollBy in c)
            .append("\" bounds=\"[").append(at[0] + b.left.toInt()).append(',').append(at[1] + b.top.toInt())
            .append("][").append(at[0] + b.right.toInt()).append(',').append(at[1] + b.bottom.toInt()).append("]\">")
        n.children.forEach { node(it, pkg, at, out) }
        out.append("</node>")
    }

    private fun esc(s: String?): String = (s ?: "")
        .replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}
