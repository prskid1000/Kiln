package app.kiln.agent

import app.kiln.Graph
import app.kiln.build.Project
import app.kiln.core.KJPretty
import app.kiln.tools.ToolContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The eval set (SPEC §13): fixed app prompts built from scratch, scored on what
 * matters — it builds, it runs without crashing, plus steps, tokens, cost and
 * time. Harness changes ship only if these numbers hold or improve. Results
 * are saved to files/evals/<timestamp>.json.
 */
object Evals {
    data class State(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val report: String = "")
    val state = MutableStateFlow(State())

    @Serializable
    data class Case(val id: String, val label: String, val prompt: String)

    @Serializable
    data class Score(
        val id: String, val built: Boolean, val running: Boolean, val crashed: Boolean,
        val steps: Int, val toolCalls: Int, val inputTokens: Long, val outputTokens: Long,
        val costUsd: Double, val seconds: Long, val error: String? = null,
    )

    val cases = listOf(
        Case("counter", "Counter", "A counter app: big number in the middle, + and − buttons, a reset button. Persist the count across restarts."),
        Case("todo", "Todo", "A todo list: add items with a text field, tick them done, swipe or button to delete, persisted. Show an empty state."),
        Case("timer", "Timer", "A countdown timer: pick minutes and seconds, start/pause/reset, and post a notification when it ends."),
        Case("tabs", "Tabs", "An app with three tabs (Home, Stats, Settings) using adaptive navigation; Settings has two switches that persist."),
        Case("listdetail", "Notes", "A notes app: list of notes, tap one to see and edit it on a detail screen (Navigation 3), add and delete notes, persisted."),
        Case("network", "Weather", "Show the current temperature for Berlin from https://api.open-meteo.com/v1/forecast?latitude=52.52&longitude=13.41&current=temperature_2m with loading and error states."),
    )

    suspend fun run(only: List<String>? = null) {
        val list = cases.filter { only == null || it.id in only }
        state.value = State(true, 0, list.size, "")
        val scores = mutableListOf<Score>()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        for ((i, c) in list.withIndex()) {
            val t0 = System.currentTimeMillis()
            // Project names allow only [a-z0-9_]: the stamp's "-" made every eval case fail to create.
            val name = "eval_${c.id}_$stamp".lowercase().replace(Regex("[^a-z0-9_]"), "_").take(40)
            scores += runCatching {
                val p = Project.create(Graph.paths.projects, name, c.label, File(Graph.toolchain.templates(), "compose"))
                // Unattended: a prompt nobody can see would hang the benchmark for good.
                val loop = Graph.kiln.newSession(p).also { it.headless = true }
                loop.send(c.prompt + "\n\nWork autonomously; do not ask questions. Verify on the device before finishing.")
                val feed = loop.feed.value
                val tools = feed.filter { it.kind == Activity.Kind.TOOL }
                val build = Graph.builds.build(p)
                var running = false; var crashed = false
                if (build.ok && Graph.warden.ready) {
                    Graph.device.install(File(build.apk!!))
                    val marker = Graph.device.logMarker()
                    Graph.device.launch(p.meta().`package`)
                    kotlinx.coroutines.delay(3000)
                    running = Graph.device.pid(p.meta().`package`) != null
                    crashed = Graph.device.lastCrash(p.meta().`package`, marker) != null
                    Graph.device.stop(p.meta().`package`)
                }
                val u = loop.usage.value
                Score(c.id, build.ok, running, crashed, loop.session.messages.count { it.role == "assistant" }, tools.size,
                    u.input + u.cacheRead + u.cacheWrite, u.output, loop.cost.value, (System.currentTimeMillis() - t0) / 1000)
            }.getOrElse {
                // Leaving the screen cancels evals: stop, don't create the remaining projects as "failures".
                if (it is kotlinx.coroutines.CancellationException) { state.value = state.value.copy(running = false); throw it }
                Score(c.id, false, false, false, 0, 0, 0, 0, 0.0, (System.currentTimeMillis() - t0) / 1000, it.message)
            }
            state.value = state.value.copy(done = i + 1, report = report(scores))
        }
        // A full disk mustn't crash Kiln or lose the report shown below.
        runCatching { File(Graph.paths.evals, "$stamp.json").writeText(KJPretty.encodeToString(ListSerializer(Score.serializer()), scores)) }
        state.value = state.value.copy(running = false, report = report(scores))
    }

    fun report(scores: List<Score>): String = buildString {
        scores.forEach { s ->
            appendLine("%-10s %s %s  %2d steps %3d tools  $%.2f  %ds%s".format(s.id,
                if (s.built) "built" else "FAIL ", if (s.running && !s.crashed) "runs " else if (s.crashed) "CRASH" else "  –  ",
                s.steps, s.toolCalls, s.costUsd, s.seconds, s.error?.let { " ($it)" } ?: ""))
        }
        if (scores.isNotEmpty()) appendLine("pass ${scores.count { it.built && it.running && !it.crashed }}/${scores.size} · $%.2f".format(scores.sumOf { it.costUsd }))
    }

    @Suppress("unused") private fun unused(c: ToolContext) = c
}
