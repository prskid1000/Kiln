package app.kiln.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.kiln.agent.Schedule
import app.kiln.agent.Schedules
import app.kiln.ui.theme.N
import app.kiln.ui.theme.T
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val PRESETS = listOf(
    "Nightly check" to "Run the app and test its main flows end to end with qa_check (write the done criteria from what " +
        "the app is for). Fix anything that crashes or doesn't work, verify the fix on the device, and summarise what you found.",
    "Polish pass" to "Look at every screen of the app with screenshots and ui_check. Fix the most visible layout, spacing, " +
        "contrast and touch-target problems (at most five, smallest safe changes), verify on the device, and summarise.",
    "Code tidy" to "Read the code and make one careful cleanup: remove dead code and duplication without changing " +
        "behaviour. Build, run, and confirm nothing changed on screen.",
)

/** A run Kiln starts by itself every day, in a new chat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleSheet(vm: KilnVM, ps: ProjectState, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember { Schedules.get(ps.project) }
    var prompt by remember { mutableStateOf(saved?.prompt ?: PRESETS[0].second) }
    var time by remember { mutableStateOf(saved?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "02:00") }
    var charging by remember { mutableStateOf(saved?.chargingOnly ?: true) }
    var enabled by remember { mutableStateOf(saved?.enabled ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf(saved?.takeIf { it.lastRun > 0 }?.let {
        "Last run ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.lastRun))}: ${it.lastResult}" }) }

    fun parsed(): Schedule? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(time.trim())
        val h = m?.groupValues?.get(1)?.toIntOrNull(); val min = m?.groupValues?.get(2)?.toIntOrNull()
        if (h == null || min == null || h > 23 || min > 59) { error = "Time as HH:MM, e.g. 02:00"; return null }
        if (prompt.isBlank()) { error = "Say what Kiln should do"; return null }
        error = null
        return Schedule(prompt.trim(), h, min, enabled, charging, saved?.lastRun ?: 0, saved?.lastResult ?: "")
    }

    ModalBottomSheet(onDismiss, containerColor = N.surface) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Scheduled run", style = T.cardTitle)
            Text("Every day at this time Kiln works on ${ps.label} by itself, in a new chat, and notifies you when it's done.",
                style = T.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KChip("On", enabled) { enabled = !enabled }
                KChip("Only while charging", charging) { charging = !charging }
            }
            KField("Time", time, { time = it }, mono = true, hint = "02:00")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PRESETS.forEach { (label, text) -> KChip(label, prompt == text) { prompt = text } }
            }
            KField("What to do", prompt, { prompt = it }, singleLine = false)
            status?.let { Text(it, style = T.label) }
            error?.let { Text(it, style = T.bodySmall.copy(color = N.danger)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KButton("Save", Tone.Accent) {
                    val s = parsed() ?: return@KButton
                    Schedules.set(ctx, ps.project, s)
                    status = if (s.enabled) "Next run: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(Schedules.next(s)))
                        else "Saved, switched off"
                }
                KButton("Run now") {
                    val s = parsed() ?: return@KButton
                    scope.launch { status = KilnVM.startRun(ctx, ps.project.name, s.prompt, freshChat = true) ?: "Started in a new chat" }
                }
                if (saved != null) KButton("Remove") { Schedules.set(ctx, ps.project, null); onDismiss() }
            }
        }
    }
}
