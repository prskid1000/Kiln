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

    /** Send [intent] the way Kiln does (an ordered broadcast to the app) and return the result data. */
    private fun ask(intent: Intent): String {
        val latch = CountDownLatch(1); var data = ""
        ctx.sendOrderedBroadcast(intent.setPackage(ctx.packageName), null, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { data = resultData.orEmpty(); latch.countDown() }
        }, null, Activity.RESULT_CANCELED, null, null)
        latch.await(5, TimeUnit.SECONDS)
        return data
    }

    private fun tree() = String(Base64.decode(ask(Intent(KilnInspector.ACTION)), Base64.DEFAULT))

    @Test fun dialogsAreVisibleAndTappable() {
        rule.runOnUiThread { KilnInspector.install(rule.activity) }
        var open by mutableStateOf(false); var saved = false
        rule.setContent {
            KilnTheme {
                Column { Text("Home screen"); KButton("Open") { open = true } }
                KDialog(open, "New expense", { open = false }, confirmLabel = "Save", onConfirm = { saved = true }) {
                    KTextField("", {}, label = "Amount")
                }
            }
        }
        assertTrue(tree().contains("Home screen"))
        rule.runOnIdle { open = true }
        rule.waitForIdle()
        val xml = tree()
        assertTrue("dialog title missing: $xml", xml.contains("New expense"))
        assertTrue("dialog field missing", xml.contains("content-desc=\"Amount\""))
        assertTrue("the covered screen should not be listed", !xml.contains("Home screen"))
        // Tap Save by its bounds, as Kiln's tap tool does.
        val save = Regex("""text="Save"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""").find(xml) ?: error("no Save button in $xml")
        val (l, t, r, b) = save.destructured
        val res = ask(Intent(KilnInspector.INPUT).putExtra("op", "tap")
            .putExtra("x", (l.toInt() + r.toInt()) / 2).putExtra("y", (t.toInt() + b.toInt()) / 2))
        rule.waitForIdle()
        assertTrue("tap result $res", saved)
        assertTrue("dialog closed", !tree().contains("New expense"))
    }
}
