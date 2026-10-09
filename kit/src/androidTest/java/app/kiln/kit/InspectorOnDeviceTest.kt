package app.kiln.kit

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Kiln drives apps through the in-app inspector: it must see and tap inside dialogs and sheets too. */
@RunWith(AndroidJUnit4::class)
class InspectorOnDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Send [intent] the way Kiln does — `am broadcast` from the shell (the inspector only accepts senders holding
     * DUMP, which the shell has) — and return the result data.
     */
    private fun ask(intent: Intent): String {
        val cmd = buildString {
            append("am broadcast -a ").append(intent.action).append(" -p ").append(ctx.packageName)
            intent.extras?.let { b ->
                for (k in b.keySet()) when (val v = b.get(k)) {
                    is Int -> append(" --ei $k $v"); is Boolean -> append(" --ez $k $v"); else -> append(" --es $k $v")
                }
            }
        }
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        val out = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        // "Broadcast completed: result=1, data=\"…\""
        return out.substringAfter("data=\"", "").substringBeforeLast("\"")
    }

    private fun tree() = String(Base64.decode(ask(Intent(KilnInspector.ACTION)), Base64.DEFAULT))

    /** Chips and segments report their selection as checked, so a tap on one can say what changed. */
    @Test fun selectionIsReported() {
        rule.setContent {
            KilnTheme {
                var pick by androidx.compose.runtime.remember { mutableStateOf(0) }
                KSegmented(listOf("Food", "Bills"), pick, { pick = it })
            }
        }
        rule.waitForIdle()
        // The selected segment, by the text on it or on its child.
        fun selected(xml: String) = Regex("""<node text="([^"]*)"[^>]*checkable="true" checked="true"""").findAll(xml)
            .map { it.groupValues[1] }.filter { it.isNotEmpty() }.toList() +
            Regex("""checkable="true" checked="true"[^>]*>\s*<node text="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toList()
        val before = tree()
        assertTrue("Food selected first: $before", "Food" in selected(before) && "Bills" !in selected(before))
        rule.onNode(androidx.compose.ui.test.hasText("Bills")).performClick()
        rule.waitForIdle()
        val after = tree()
        assertTrue("Bills selected after the tap: $after", "Bills" in selected(after) && "Food" !in selected(after))
    }

    @Test fun dialogsAreVisibleAndTappable() {
        rule.runOnUiThread { KilnInspector.install(rule.activity) }
        var open by mutableStateOf(false); var saved = false
        var amount by mutableStateOf("")
        rule.setContent {
            KilnTheme {
                Column { Text("Home screen"); KButton("Open") { open = true } }
                KDialog(open, "New expense", { open = false }, confirmLabel = "Save", onConfirm = { saved = true }) {
                    KTextField(amount, { amount = it }, label = "Amount")
                }
            }
        }
        assertTrue(tree().contains("Home screen"))
        rule.runOnIdle { open = true }
        rule.waitForIdle()
        var xml = tree()
        assertTrue("dialog title missing: $xml", xml.contains("New expense"))
        assertTrue("dialog field missing", xml.contains("content-desc=\"Amount\""))
        assertTrue("the covered screen should not be listed", !xml.contains("Home screen"))
        // Typing reports what the field holds; a second type adds, replace sets.
        val field = Regex("""content-desc="Amount"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""").find(xml) ?: error("no Amount field")
        val (fl, ft, fr, fb) = field.destructured
        ask(Intent(KilnInspector.INPUT).putExtra("op", "tap").putExtra("x", (fl.toInt() + fr.toInt()) / 2).putExtra("y", (ft.toInt() + fb.toInt()) / 2))
        rule.waitForIdle()
        assertTrue(ask(Intent(KilnInspector.INPUT).putExtra("op", "text").putExtra("text", "12")).contains("Amount — it now holds “12”"))
        rule.waitForIdle()
        assertTrue(ask(Intent(KilnInspector.INPUT).putExtra("op", "text").putExtra("text", "3")).contains("“123”"))
        rule.waitForIdle()
        assertTrue(ask(Intent(KilnInspector.INPUT).putExtra("op", "text").putExtra("text", "5").putExtra("replace", true)).contains("“5”"))
        rule.waitForIdle()
        assertTrue("field value $amount", amount == "5")
        // Tap Save by its bounds, as Kiln's tap tool does — read fresh: the keyboard moved the dialog.
        xml = tree()
        val save = Regex("""text="Save"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""").find(xml) ?: error("no Save button in $xml")
        val (l, t, r, b) = save.destructured
        val res = ask(Intent(KilnInspector.INPUT).putExtra("op", "tap")
            .putExtra("x", (l.toInt() + r.toInt()) / 2).putExtra("y", (t.toInt() + b.toInt()) / 2))
        rule.waitForIdle()
        assertTrue("tap result $res", saved)
        assertTrue("dialog closed", !tree().contains("New expense"))
    }
}
