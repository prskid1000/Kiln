package app.kiln.agent

import android.util.Log
import app.kiln.build.Project
import app.kiln.core.arrOf
import app.kiln.core.compact
import app.kiln.core.obj
import app.kiln.core.str
import app.kiln.llm.Adapter
import app.kiln.llm.ModelEvent
import app.kiln.llm.ModelRequest
import app.kiln.llm.ModelTurn
import app.kiln.llm.Msg
import app.kiln.llm.Profile
import app.kiln.llm.priceFor
import app.kiln.llm.ProviderException
import app.kiln.llm.Providers
import app.kiln.llm.Stop
import app.kiln.llm.Usage
import app.kiln.tools.validateInput
import app.kiln.tools.Policy
import app.kiln.tools.SessionState
import app.kiln.tools.Tool
import app.kiln.tools.ToolContext
import app.kiln.tools.ToolRegistry
import app.kiln.tools.ToolResult
import app.kiln.tools.Trait
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** One line in the activity feed. */
data class Activity(
    val id: Int,
    val kind: Kind,
    val text: String = "",
    val tool: String? = null,
    val input: String? = null,
    val status: Status = Status.DONE,
    val summary: String = "",
    val progress: String = "",
    val images: List<ByteArray> = emptyList(),
    val ms: Long = 0,
    /** Names of non-image files attached to a USER message. */
    val files: List<String> = emptyList(),
    /** MP4 recorded by the tool (QA runs), shown as a playable clip. */
    val video: String? = null,
    /** Transcript index of a USER message (for rewind / fork / change review); -1 if none. */
    val msgIndex: Int = -1,
) {
    enum class Kind { USER, ASSISTANT, THINKING, TOOL, NOTICE, ERROR }
    enum class Status { RUNNING, DONE, FAILED, DENIED, STOPPED }
}

data class ApprovalRequest(val tool: String, val input: String, val answer: CompletableDeferred<Pair<Boolean, Boolean>>) // (allow, forSession)
data class Question(val text: String, val options: List<String>, val answer: CompletableDeferred<String>, val kind: String = "text",
                    val questions: List<app.kiln.tools.AskQ> = emptyList())

/** Separates the answers of a multi-question card in [Question.answer]. */
const val ANSWER_SEP = "\u001F"

/**
 * The harness loop (SPEC §3). One instance per session; [send] runs a user
 * turn to completion (model ↔ tools) and streams everything to [feed].
 */
class AgentLoop(
    val project: Project,
    val session: Session,
    private val providers: Providers,
    private val registry: ToolRegistry,
    private val tools: List<Tool>,
    private val settings: SettingsStore,
    private val role: String = "agent",
) {
    private val ids = AtomicInteger()
    private val _feed = MutableStateFlow<List<Activity>>(emptyList())
    val feed: StateFlow<List<Activity>> = _feed
    val running = MutableStateFlow(false)
    /** When the current run began (for the elapsed time shown while it works). */
    @Volatile var startedAt = 0L; private set
    val approval = MutableStateFlow<ApprovalRequest?>(null)
    val question = MutableStateFlow<Question?>(null)
    val usage = MutableStateFlow(session.meta.usage)
    val cost = MutableStateFlow(session.meta.costUsd)
    val todos = MutableStateFlow<List<SessionState.Todo>>(emptyList())
    /** False once a model without a known price was used: the cost shown is then a lower bound. */
    val costKnown = MutableStateFlow(true)

    enum class Mode { BUILD, PLAN }
    /** The latest plan from a Plan-mode request: the UI offers "Build this plan". */
    val plan = MutableStateFlow<String?>(null)
    /** Messages typed while the agent works, delivered at its next step instead of waiting for the end. */
    private val steering = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private var mode = Mode.BUILD
    /** Done criteria for a goal run: the turn may only end once a check says they're met. */
    private var goal: String? = null
    private var goalChecks = 0

    private val state = SessionState()
    /** How often this request pushed back on ending with a broken build (see the stop guard in [loop]). */
    private var stopGuards = 0
    private var unfinishedGuards = 0
    private val approvalLock = kotlinx.coroutines.sync.Mutex()
    private val sessionAllowed = mutableSetOf<String>()
    private val spillN = AtomicInteger(session.spillDir.listFiles()?.size ?: 0)

    init { replay() }

    // ------------------------------------------------------------ feed

    private fun add(a: Activity): Int { _feed.update { it + a }; return a.id }
    private fun next(kind: Activity.Kind, text: String = "", tool: String? = null, input: String? = null,
                     status: Activity.Status = Activity.Status.DONE) =
        add(Activity(ids.incrementAndGet(), kind, text, tool, input, status))
    private fun update(id: Int, f: (Activity) -> Activity) { _feed.update { l -> l.map { if (it.id == id) f(it) else it } } }

    /** Rebuild the feed from a stored transcript (tool results matched to their calls). */
    /** A run cut short: say so now, and again when this chat is reopened (it can be continued). */
    private fun stoppedWith(text: String, error: Boolean = false) {
        next(if (error) Activity.Kind.ERROR else Activity.Kind.NOTICE, text)
        runCatching { session.updateMeta { it.copy(stopNotice = text, stopIsError = error) } }
    }

    private fun replay() {
        val results = HashMap<String, JsonObject>()
        session.messages.filter { it.role == "user" }.forEach { m -> m.content.forEach { b ->
            (b as? JsonObject)?.takeIf { it.str("type") == "tool_result" }?.let { results[it.str("tool_use_id") ?: ""] = it } } }
        for ((mi, m) in session.messages.withIndex()) for (b in m.content) {
            val o = b as? JsonObject ?: continue
            when (o.str("type")) {
                "text" -> if (m.role == "user") {
                              val t = o.str("text").orEmpty()
                              // Attachments ride on the user's bubble as chips, not as giant text.
                              val att = Attachments.nameOf(t)
                              if (att != null) attachToLastUser(files = listOf(att))
                              else if (t.startsWith(STEER_PREFIX)) next(Activity.Kind.USER, t.removePrefix(STEER_PREFIX))
                              else if (!t.startsWith("<system-reminder>"))
                                  next(Activity.Kind.USER, t).let { id -> update(id) { it.copy(msgIndex = mi) } }
                          } else if (m.role == "assistant") next(Activity.Kind.ASSISTANT, o.str("text") ?: "")
                "image" -> if (m.role == "user") (o["source"] as? JsonObject)?.str("data")?.let {
                    attachToLastUser(images = listOf(java.util.Base64.getDecoder().decode(it)))
                }
                "tool_use" -> {
                    val r = results[o.str("id")]
                    val err = r?.get("is_error")?.toString() == "true"
                    val text = r?.get("content").let { c -> (c as? JsonPrimitive)?.content
                        ?: (c as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.str("text") } } ?: ""
                    add(Activity(ids.incrementAndGet(), Activity.Kind.TOOL, tool = o.str("name"), input = o["input"]?.compact(),
                        status = if (text == "interrupted by the user") Activity.Status.STOPPED else if (err) Activity.Status.FAILED else Activity.Status.DONE, summary = text.lineSequence().firstOrNull()?.take(140) ?: ""))
                }
            }
        }
        val notice = session.meta.stopNotice
        if (notice.isNotEmpty()) next(if (session.meta.stopIsError) Activity.Kind.ERROR else Activity.Kind.NOTICE, notice)
        // A transcript that ends on the user's side (tool results, a message) was cut off mid-run:
        // Kiln was closed, updated or killed while it worked.
        else if (session.messages.lastOrNull()?.role == "user") next(Activity.Kind.NOTICE, "Interrupted — Kiln was closed while it worked.")
    }

    // ------------------------------------------------------------ turn

    /** Images / file names on the newest USER bubble (an attachment arrives after its message text). */
    private fun attachToLastUser(images: List<ByteArray> = emptyList(), files: List<String> = emptyList()) {
        val last = _feed.value.lastOrNull { it.kind == Activity.Kind.USER }
        if (last == null) next(Activity.Kind.USER, "").let { id -> update(id) { it.copy(images = images, files = files) } }
        else update(last.id) { it.copy(images = it.images + images, files = it.files + files) }
    }

    /**
     * Run one user request. [mode] PLAN gives the model read-only tools and asks for a spec ending in
     * done criteria; a [goal] (those criteria) keeps the turn going until a separate check says they're met.
     */
    suspend fun send(userText: String, attachments: List<Attachment> = emptyList(), mode: Mode = Mode.BUILD, goal: String? = null) {
        // Atomic: a double-tap must not start two loops on one transcript.
        if (!running.compareAndSet(expect = false, update = true)) return
        if (session.meta.stopNotice.isNotEmpty()) session.updateMeta { it.copy(stopNotice = "", stopIsError = false) }
        startedAt = System.currentTimeMillis()
        stopGuards = 0; unfinishedGuards = 0; goalChecks = 0
        this.mode = mode; this.goal = goal?.takeIf { it.isNotBlank() }
        // A process killed mid-tool leaves tool_use without tool_result, which every provider rejects.
        closeDanglingToolUses()
        try {
            // Snapshot the sources before this turn: rewind and change review are keyed to it.
            runCatching { Turns.snapshot(session, project, session.messages.size) }
            val id = next(Activity.Kind.USER, userText)
            update(id) { it.copy(msgIndex = session.messages.size) }
            val extra = Attachments.blocks(project, attachments)
            update(id) { it.copy(files = attachments.filterNot { a -> a.mime.startsWith("image/") }.map { a -> a.name },
                images = attachments.filter { a -> a.mime.startsWith("image/") }.map { a -> a.bytes }) }
            val blocks = listOfNotNull(userText.takeIf { it.isNotBlank() }?.let { obj("type" to "text", "text" to it) }) + extra +
                listOfNotNull(if (mode == Mode.PLAN) obj("type" to "text", "text" to PLAN_REMINDER) else null) +
                listOfNotNull(this.goal?.let { obj("type" to "text", "text" to "<system-reminder>This is a goal run. The turn ends only " +
                    "when these done criteria are verified on the device:\n$it</system-reminder>") })
            session.append(Msg("user", arrOf(blocks)))
            val title = userText.ifBlank { attachments.joinToString { it.name } }
            if (session.meta.title.isBlank()) session.updateMeta { it.copy(title = title.take(60)) }
            loop()
            if (mode == Mode.PLAN) plan.value = session.messages.lastOrNull { it.role == "assistant" }?.content
                ?.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }?.joinToString("\n")?.trim()
        } catch (e: CancellationException) {
            _feed.value = _feed.value.map { if (it.status == Activity.Status.RUNNING) it.copy(status = Activity.Status.STOPPED) else it }
            stoppedWith("Stopped.")
            closeDanglingToolUses()
            throw e
        } catch (e: Throwable) {
            Log.e("Kiln", "agent loop", e)
            stoppedWith(e.message ?: e.toString(), error = true)
            closeDanglingToolUses()
        } finally {
            running.value = false
        }
    }

    private suspend fun loop() {
        val cfg = settings.value.merged(project.dir)
        var steps = 0
        while (true) {
            if (++steps > cfg.maxSteps) { stoppedWith("Stopped after ${cfg.maxSteps} steps (limit in Settings)."); return }
            if (cost.value >= cfg.sessionUsd) { next(Activity.Kind.NOTICE, "Session spending cap reached (\$${"%.2f".format(cfg.sessionUsd)})."); return }
            if (settings.spentToday() >= cfg.dailyUsd) { next(Activity.Kind.NOTICE, "Daily spending cap reached (\$${"%.2f".format(cfg.dailyUsd)})."); return }

            val turn = callModel(cfg) ?: return
            when (turn.second.stop) {
                Stop.REFUSAL -> { next(Activity.Kind.ERROR, "The model declined this request${turn.second.refusal?.let { ": $it" } ?: ""}."); return }
                Stop.PAUSE_TURN -> continue
                else -> {}
            }
            val uses = turn.second.content.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "tool_use" } }
            if (uses.isEmpty()) {
                if (turn.second.stop == Stop.MAX_TOKENS) {
                    session.append(Msg("user", arrOf(listOf(obj("type" to "text", "text" to "Your reply was cut off at the output limit. Continue from where you stopped.")))))
                    continue
                }
                // Stop guard: ending the turn while the project doesn't build is almost always a
                // mistake (a local model once "heard" a stop nobody sent). Push back, twice at most.
                val lb = state.lastBuild
                if (lb != null && !lb.ok && stopGuards < 2) {
                    stopGuards++
                    next(Activity.Kind.NOTICE, "The build is still failing — asking the agent to keep going.")
                    session.append(Msg("user", arrOf(listOf(obj("type" to "text", "text" to
                        "<system-reminder>Nobody asked you to stop. The project does not build: the last check had " +
                        "${lb.errors.size} error(s). Fix them (see the import hints in that result), run check again, " +
                        "then continue with the task. Only stop if you truly cannot fix it — and then say why.</system-reminder>")))))
                    continue
                }
                // Messages the user typed while the model was finishing: answer them, don't end.
                drainSteering()?.let { session.append(Msg("user", arrOf(it))); continue }
                // Unfinished guard: smaller models often end a turn with "Let me fix X…" and no call, or
                // with todo items still open. Unless it's asking the user something, push it on (twice at most).
                val said = turn.second.content.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }
                    .joinToString("\n").trim()
                val open = state.todos.filter { it.status != "completed" }
                val announces = ANNOUNCES.containsMatchIn(said.takeLast(300))
                if (mode == Mode.BUILD && unfinishedGuards < 2 && !said.trimEnd().endsWith("?") && (open.isNotEmpty() || announces)) {
                    unfinishedGuards++
                    next(Activity.Kind.NOTICE, if (open.isNotEmpty()) "${open.size} to-do item(s) still open — asking the agent to keep going."
                        else "The agent said what it would do next but stopped — asking it to continue.")
                    session.append(Msg("user", arrOf(listOf(obj("type" to "text", "text" to
                        "<system-reminder>Nobody asked you to stop. " +
                        (if (open.isNotEmpty()) "These to-do items are not done: ${open.joinToString("; ") { it.text }}. " else "") +
                        "If you said you would do something next, do it now with a tool call. When everything is really " +
                        "done, mark the to-dos completed and give the summary. If you can't continue, say why.</system-reminder>")))))
                    continue
                }
                // Goal run: a separate check decides whether the done criteria are actually met.
                val g = goal
                if (g != null && mode == Mode.BUILD && goalChecks < 4) {
                    goalChecks++
                    val (met, why) = checkGoal(g)
                    if (!met) {
                        next(Activity.Kind.NOTICE, "Goal not met yet — $why")
                        session.append(Msg("user", arrOf(listOf(obj("type" to "text", "text" to
                            "<system-reminder>The goal isn't met yet: $why\nKeep working until every done criterion is " +
                            "verified on the device (run_app, ui_tree, tap, qa_check).</system-reminder>")))))
                        continue
                    }
                    next(Activity.Kind.NOTICE, "Goal met ✓")
                }
                return
            }
            val results = runTools(uses, cfg, truncated = turn.second.stop == Stop.MAX_TOKENS)
            // Queued user messages ride along with the tool results: the model sees them at its next step.
            session.append(Msg("user", JsonArray(results + (drainSteering() ?: emptyList()))))
        }
    }

    /** User messages typed during the run, as text blocks (null when there are none). */
    private fun drainSteering(): List<JsonObject>? {
        val out = generateSequence { steering.poll() }.toList()
        if (out.isEmpty()) return null
        return out.map { obj("type" to "text", "text" to STEER_PREFIX + it) }
    }

    /** Add a message to the running request; it reaches the model at its next step. */
    fun steer(text: String) {
        if (text.isBlank()) return
        steering += text
        next(Activity.Kind.USER, text)
    }

    /**
     * Ask the model, in a separate small call without tools, whether the done criteria are met given
     * what the agent last reported and the latest device evidence. A fresh judgement, not the
     * builder grading its own work in the same breath.
     */
    private suspend fun checkGoal(criteria: String): Pair<Boolean, String> {
        val binding = providers.roles["subagent"] ?: providers.roles[role] ?: return true to ""
        val (profile, model) = providers.resolve("${binding.profile}:${binding.model}") ?: return true to ""
        val evidence = session.messages.takeLast(8).joinToString("\n\n") { m ->
            m.content.joinToString("\n") { b ->
                val o = b as? JsonObject
                when (o?.str("type")) {
                    "text" -> o.str("text") ?: ""
                    "tool_use" -> "[called ${o.str("name")}]"
                    "tool_result" -> "[result] " + (o["content"].let { c -> (c as? JsonPrimitive)?.content
                        ?: (c as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.str("text") } } ?: "").take(1500)
                    else -> ""
                }
            }
        }.takeLast(12_000)
        val req = ModelRequest(model = model,
            system = "You verify whether an Android app meets its done criteria, from the build/device evidence given. " +
                "Be strict: unverified means not met. Answer with MET or NOT MET on the first line, then one short reason.",
            messages = listOf(Msg("user", arrOf(listOf(obj("type" to "text", "text" to "Done criteria:\n$criteria\n\nEvidence:\n$evidence"))))),
            tools = emptyList(), maxTokens = 300, effort = "low")
        val turn = runCatching { providers.adapter(profile).stream(req) {} }.getOrNull() ?: return true to "could not check"
        val c = turn.usage.cost(profile.priceFor(model)); cost.value += c; settings.addSpend(c)
        val text = turn.content.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }.joinToString("\n").trim()
        val first = text.lineSequence().firstOrNull()?.uppercase() ?: ""
        return (first.startsWith("MET") && !first.startsWith("NOT")) to text.lines().drop(1).joinToString(" ").ifBlank { text }.take(200)
    }

    /** One model call with streaming into the feed, retries, and the role's fallback chain. Returns (origin, turn). */
    private suspend fun callModel(cfg: Settings): Pair<String, ModelTurn>? {
        val binding = providers.roles[role] ?: error("no model configured for role '$role' — Settings → Models")
        val chain = listOf("${binding.profile}:${binding.model}") + binding.fallback
        var lastError: Throwable? = null
        for (spec in chain) {
            val (profile, model) = providers.resolve(spec) ?: continue
            if (profile.caps.tools.not()) continue
            val adapter = providers.adapter(profile)
            // Retry only what can succeed on retry; anything else moves on to the fallback model.
            attempts@ for (attempt in 0..cfg.maxRetries) {
                try {
                    return stream(adapter, profile, model, cfg)
                } catch (e: CancellationException) { throw e
                } catch (e: ProviderException) {
                    lastError = e
                    if (!e.retryable || attempt == cfg.maxRetries) break@attempts
                    val wait = 2_000L * (1 shl attempt)
                    next(Activity.Kind.NOTICE, "${profile.label}: ${e.message?.take(120)} — retrying in ${wait / 1000}s")
                    delay(wait)
                } catch (e: Throwable) { lastError = e; break@attempts }
            }
            if (spec != chain.last()) next(Activity.Kind.NOTICE, "${profile.label} failed (${lastError?.message?.take(100)}); falling back.")
        }
        stoppedWith("Model call failed: ${lastError?.message ?: "no usable model"}", error = true)
        return null
    }

    private suspend fun stream(adapter: Adapter, profile: Profile, model: String, cfg: Settings): Pair<String, ModelTurn> {
        val origin = "${profile.id}|$model"
        // Plan mode: the model only sees tools that can't change anything.
        // Deferred tools appear once tool_search has loaded them.
        val offered = tools.filter { !it.deferred || it.name in state.loadedTools }
        val specs = registry.specs(if (mode == Mode.PLAN) offered.filter { Trait.READ_ONLY in it.traits } else offered)
        val req = ModelRequest(
            model = model, system = session.meta.systemPrompt,
            messages = ContextFit.fit(session.messages, session.meta.systemPrompt, profile.caps.contextWindow), tools = specs,
            maxTokens = minOf(profile.caps.maxOutput, 64_000),
            effort = if (role == "agent") cfg.effort else cfg.subagentEffort,
            taskBudgetTokens = cfg.taskBudgetTokens,
            serverTools = listOf(obj("type" to "web_search_20260209", "name" to "web_search", "max_uses" to 5)),
        )
        var textId: Int? = null; var thinkId: Int? = null
        val turn = adapter.stream(req) { ev ->
            when (ev) {
                is ModelEvent.Text -> {
                    val id = textId ?: next(Activity.Kind.ASSISTANT, "", status = Activity.Status.RUNNING).also { textId = it }
                    update(id) { it.copy(text = it.text + ev.delta) }
                }
                is ModelEvent.Thinking -> {
                    val id = thinkId ?: next(Activity.Kind.THINKING, "", status = Activity.Status.RUNNING).also { thinkId = it }
                    update(id) { it.copy(text = it.text + ev.delta) }
                }
                is ModelEvent.ToolStart -> { textId = null }
                is ModelEvent.Status -> next(Activity.Kind.NOTICE, ev.message)
                is ModelEvent.ToolArgs -> {}
            }
        }
        textId?.let { id -> update(id) { it.copy(status = Activity.Status.DONE) } }
        thinkId?.let { id -> update(id) { it.copy(status = Activity.Status.DONE) } }
        session.append(Msg("assistant", turn.content, origin = origin, providerState = turn.providerState))
        val c = turn.usage.cost(profile.priceFor(model))
        if (profile.priceFor(model) == null) costKnown.value = false
        usage.value = usage.value + turn.usage
        cost.value += c
        settings.addSpend(c)
        session.updateMeta { it.copy(usage = usage.value, costUsd = cost.value) }
        return origin to turn
    }

    // ------------------------------------------------------------ tools

    private fun ctx(activityId: Int) = object : ToolContext {
        override val project = this@AgentLoop.project
        override val sessionId = session.meta.id
        override val state = this@AgentLoop.state
        override val spillDir = session.spillDir
        override fun progress(line: String) = update(activityId) { it.copy(progress = line) }
        override fun addCost(usd: Double) {
            cost.value += usd
            session.updateMeta { it.copy(costUsd = cost.value) }
        }
        override suspend fun ask(question: String, options: List<String>): String {
            val q = Question(question, options, CompletableDeferred())
            this@AgentLoop.question.value = q
            return try { q.answer.await() } finally { this@AgentLoop.question.value = null }
        }
        override suspend fun askMany(questions: List<app.kiln.tools.AskQ>): List<String> {
            val first = questions.first()
            val q = Question(first.question, first.options.map { it.label }, CompletableDeferred(), kind = "multi", questions = questions)
            this@AgentLoop.question.value = q
            val raw = try { q.answer.await() } finally { this@AgentLoop.question.value = null }
            val parts = raw.split(ANSWER_SEP)
            return questions.indices.map { parts.getOrNull(it)?.trim().orEmpty() }
        }
        override suspend fun chooseLook(appName: String?): String {
            val q = Question("Pick a look for ${appName ?: "this app"}", emptyList(), CompletableDeferred(), kind = "look")
            this@AgentLoop.question.value = q
            return try { q.answer.await() } finally { this@AgentLoop.question.value = null }
        }
        override fun spill(text: String, maxChars: Int): String {
            if (text.length <= maxChars) return text
            val id = "out-${spillN.incrementAndGet()}"
            File(spillDir, "$id.txt").writeText(text)
            val head = text.take(maxChars * 2 / 3); val tail = text.takeLast(maxChars / 3)
            return "$head\n\n… [${text.length - head.length - tail.length} chars omitted — full text saved as $id; use read_output] …\n\n$tail"
        }
    }

    private suspend fun runTools(uses: List<JsonObject>, cfg: Settings, truncated: Boolean): List<JsonObject> = coroutineScope {
        val byName = tools.associateBy { it.name }
        // Consecutive parallel-safe calls run together; anything else runs alone, in order.
        val results = arrayOfNulls<JsonObject>(uses.size)
        var i = 0
        while (i < uses.size) {
            val group = mutableListOf(i)
            val t0 = byName[uses[i].str("name")]
            if (t0 != null && Trait.PARALLEL_SAFE in t0.traits)
                while (group.last() + 1 < uses.size && byName[uses[group.last() + 1].str("name")]?.traits?.contains(Trait.PARALLEL_SAFE) == true)
                    group += group.last() + 1
            group.map { idx -> async { idx to runOne(uses[idx], byName, cfg, truncated) } }.awaitAll()
                .forEach { (idx, r) -> results[idx] = r }
            i = group.last() + 1
        }
        results.map { it!! }
    }

    private suspend fun runOne(use: JsonObject, byName: Map<String, Tool>, cfg: Settings, truncated: Boolean): JsonObject {
        val id = use.str("id") ?: ""
        val name = use.str("name") ?: ""
        // Strict schemas make optional fields nullable; null means "not given" for every tool (incl. MCP).
        val input = (use["input"] as? JsonObject)?.let { o -> JsonObject(o.filterValues { it !is kotlinx.serialization.json.JsonNull }) } ?: obj()
        val aid = next(Activity.Kind.TOOL, tool = name, input = input.compact(), status = Activity.Status.RUNNING)
        val t0 = System.currentTimeMillis()
        fun result(r: ToolResult, status: Activity.Status): JsonObject {
            update(aid) { it.copy(status = status, summary = r.summary, images = r.images, video = r.video, ms = System.currentTimeMillis() - t0, progress = "") }
            return obj("type" to "tool_result", "tool_use_id" to id, "content" to r.blocks(), "is_error" to if (r.isError) true else null)
        }
        val tool = byName[name] ?: return result(ToolResult.error("unknown tool $name"), Activity.Status.FAILED)
        if (input["_invalid_json"] != null || truncated && input.isEmpty())
            return result(ToolResult.error("your tool input was cut off or invalid JSON — send it again (smaller, if it was large)"), Activity.Status.FAILED)
        validate(tool, input)?.let { return result(ToolResult.error(it), Activity.Status.FAILED) }
        if (mode == Mode.PLAN && Trait.READ_ONLY !in tool.traits)
            return result(ToolResult.error("plan mode is read-only: put this change in the plan instead"), Activity.Status.DENIED)

        when (if (name in sessionAllowed) Policy.ALLOW else registry.policy(tool, cfg.approval)) {
            Policy.DENY -> return result(ToolResult.error("$name is disabled by policy"), Activity.Status.DENIED)
            Policy.ASK -> {
                // One prompt at a time: parallel tools would otherwise overwrite each other's request.
                val (ok, always) = approvalLock.withLock {
                    if (name in sessionAllowed) return@withLock true to true
                    val req = ApprovalRequest(name, input.compact(), CompletableDeferred())
                    approval.value = req
                    try { req.answer.await() } finally { approval.value = null }
                }
                if (!ok) return result(ToolResult.error("the user denied this $name call"), Activity.Status.DENIED)
                if (always) sessionAllowed += name
            }
            Policy.ALLOW -> {}
        }

        var r = try {
            withTimeout(tool.timeoutMs) { tool.run(ctx(aid), input) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) { ToolResult.error("$name timed out after ${tool.timeoutMs / 1000}s")
        } catch (e: CancellationException) { throw e
        } catch (e: Throwable) { ToolResult.error("$name failed: ${e.message ?: e}") }

        // Hooks: "postTool:<name>" → run those tools and append their output (SPEC §3).
        if (!r.isError) for (h in cfg.hooks["postTool:$name"].orEmpty()) {
            val ht = byName[h] ?: continue
            // Hooks are calls too: they get no more rights than the model would.
            if (registry.policy(ht, cfg.approval) != Policy.ALLOW && h !in sessionAllowed) {
                r = r.copy(text = r.text + "\n\n[hook $h skipped: it needs approval]"); continue
            }
            update(aid) { it.copy(progress = "hook: $h") }
            val hr = runCatching { withTimeout(ht.timeoutMs) { ht.run(ctx(aid), obj()) } }.getOrElse { ToolResult.error(it.message ?: "hook failed") }
            r = r.copy(text = r.text + "\n\n[hook $h]\n" + hr.text, images = r.images + hr.images)
        }
        todos.value = state.todos
        return result(r, if (r.isError) Activity.Status.FAILED else Activity.Status.DONE)
    }

    /** Client-side schema check: required keys present, no unknown keys, basic types. */
    private fun validate(tool: Tool, input: JsonObject): String? = validateInput(tool, input)

    /** After a cancel or crash mid-tools, every tool_use must still get a tool_result (history stays valid). */
    private fun closeDanglingToolUses() {
        val last = session.messages.lastOrNull() ?: return
        if (last.role != "assistant") return
        val uses = last.content.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "tool_use" }?.str("id") }
        if (uses.isEmpty()) return
        session.append(Msg("user", JsonArray(uses.map { obj("type" to "tool_result", "tool_use_id" to it,
            "content" to "interrupted by the user", "is_error" to true) })))
    }

    fun answerApproval(allow: Boolean, always: Boolean = false) { approval.value?.answer?.complete(allow to always) }
    fun answerQuestion(text: String) { question.value?.answer?.complete(text) }

    /** Run a self-contained sub-task on a fresh transcript and return its final text (subagent tool). */
    companion object {
        /** Appended to a Plan-mode request: what a plan must contain (the last part feeds goal runs and QA). */
        const val PLAN_REMINDER = "<system-reminder>PLAN MODE — change nothing. Investigate if useful (read files, " +
            "sdk_lookup, kit_search), then reply with a plan in Markdown: ## What it does (2–3 lines), ## Screens " +
            "(each with its contents and actions), ## Data (what is stored, where), ## Done criteria (a checklist " +
            "of observable behaviours on the device, e.g. \"tapping Add 250ml raises the total by 250\"). Reply with " +
            "the plan only.</system-reminder>"

        suspend fun headless(
            project: Project, sessionsRoot: File, providers: Providers, registry: ToolRegistry, tools: List<Tool>,
            settings: SettingsStore, systemPrompt: String, task: String, role: String,
            /** The answer must contain this; if the helper stops without it, it is told to finish (twice at most). */
            finished: Regex? = null, unfinished: String = "",
        ): Triple<String, Usage, Double> {
            val s = Session.create(File(sessionsRoot, ".sub").apply { mkdirs() }, project.name, systemPrompt)
            val loop = AgentLoop(project, s, providers, registry, tools, settings, role)
            fun answer() = s.messages.lastOrNull { it.role == "assistant" }?.content
                ?.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }?.joinToString("\n")
            loop.send(task)
            // A model may end its turn on "Let me check…" without doing it.
            var nudges = 0
            while (finished != null && nudges < 2 && answer()?.let { finished.containsMatchIn(it) } != true) {
                nudges++
                loop.send(unfinished)
            }
            return Triple(answer() ?: "(no answer)", loop.usage.value, loop.cost.value)
        }
    }
}

/** Marks a message the user sent while the agent was working (delivered at its next step). */
private const val STEER_PREFIX = "[Message from the user while you were working] "

/** A reply that ends by announcing a next step ("Let me fix the dialogs…") instead of taking it. */
private val ANNOUNCES = Regex("""(?i)(\blet me\b|\bi'll\b|\bi will\b|\bnow i\b|\bnext,? i\b|\bi'm going to\b|\bgoing to\b)[^\n]{0,160}[.…:]?\s*$""")
