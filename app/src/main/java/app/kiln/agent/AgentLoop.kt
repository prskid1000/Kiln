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
import app.kiln.llm.ProviderException
import app.kiln.llm.Providers
import app.kiln.llm.Stop
import app.kiln.llm.Usage
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
) {
    enum class Kind { USER, ASSISTANT, THINKING, TOOL, NOTICE, ERROR }
    enum class Status { RUNNING, DONE, FAILED, DENIED }
}

data class ApprovalRequest(val tool: String, val input: String, val answer: CompletableDeferred<Pair<Boolean, Boolean>>) // (allow, forSession)
data class Question(val text: String, val options: List<String>, val answer: CompletableDeferred<String>)

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
    val approval = MutableStateFlow<ApprovalRequest?>(null)
    val question = MutableStateFlow<Question?>(null)
    val usage = MutableStateFlow(session.meta.usage)
    val cost = MutableStateFlow(session.meta.costUsd)
    val todos = MutableStateFlow<List<SessionState.Todo>>(emptyList())

    private val state = SessionState()
    private val sessionAllowed = mutableSetOf<String>()
    private val spillN = AtomicInteger(session.spillDir.listFiles()?.size ?: 0)

    init { replay() }

    // ------------------------------------------------------------ feed

    private fun add(a: Activity): Int { _feed.value = _feed.value + a; return a.id }
    private fun next(kind: Activity.Kind, text: String = "", tool: String? = null, input: String? = null,
                     status: Activity.Status = Activity.Status.DONE) =
        add(Activity(ids.incrementAndGet(), kind, text, tool, input, status))
    private fun update(id: Int, f: (Activity) -> Activity) { _feed.value = _feed.value.map { if (it.id == id) f(it) else it } }

    /** Rebuild the feed from a stored transcript (tool results matched to their calls). */
    private fun replay() {
        val results = HashMap<String, JsonObject>()
        session.messages.filter { it.role == "user" }.forEach { m -> m.content.forEach { b ->
            (b as? JsonObject)?.takeIf { it.str("type") == "tool_result" }?.let { results[it.str("tool_use_id") ?: ""] = it } } }
        for (m in session.messages) for (b in m.content) {
            val o = b as? JsonObject ?: continue
            when (o.str("type")) {
                "text" -> if (m.role == "user") { if (!o.str("text").orEmpty().startsWith("<system-reminder>")) next(Activity.Kind.USER, o.str("text") ?: "") }
                          else if (m.role == "assistant") next(Activity.Kind.ASSISTANT, o.str("text") ?: "")
                "tool_use" -> {
                    val r = results[o.str("id")]
                    val err = r?.get("is_error")?.toString() == "true"
                    val text = (r?.get("content") as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.str("text") } ?: ""
                    add(Activity(ids.incrementAndGet(), Activity.Kind.TOOL, tool = o.str("name"), input = o["input"]?.compact(),
                        status = if (err) Activity.Status.FAILED else Activity.Status.DONE, summary = text.lineSequence().firstOrNull()?.take(140) ?: ""))
                }
            }
        }
    }

    // ------------------------------------------------------------ turn

    suspend fun send(userText: String) {
        if (running.value) return
        running.value = true
        try {
            next(Activity.Kind.USER, userText)
            session.append(Msg("user", arrOf(listOf(obj("type" to "text", "text" to userText)))))
            if (session.meta.title.isBlank()) session.updateMeta { it.copy(title = userText.take(60)) }
            loop()
        } catch (e: CancellationException) {
            next(Activity.Kind.NOTICE, "Stopped.")
            closeDanglingToolUses()
            throw e
        } catch (e: Throwable) {
            Log.e("Kiln", "agent loop", e)
            next(Activity.Kind.ERROR, e.message ?: e.toString())
            closeDanglingToolUses()
        } finally {
            running.value = false
        }
    }

    private suspend fun loop() {
        val cfg = settings.value.merged(project.dir)
        var steps = 0
        while (true) {
            if (++steps > cfg.maxSteps) { next(Activity.Kind.NOTICE, "Stopped after ${cfg.maxSteps} steps (limit in Settings)."); return }
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
                return
            }
            val results = runTools(uses, cfg, truncated = turn.second.stop == Stop.MAX_TOKENS)
            session.append(Msg("user", JsonArray(results)))
        }
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
            repeat(cfg.maxRetries + 1) { attempt ->
                try {
                    return stream(adapter, profile, model, cfg)
                } catch (e: CancellationException) { throw e
                } catch (e: ProviderException) {
                    lastError = e
                    if (!e.retryable) return@repeat
                    val wait = 2_000L * (1 shl attempt)
                    next(Activity.Kind.NOTICE, "${profile.label}: ${e.message?.take(120)} — retrying in ${wait / 1000}s")
                    delay(wait)
                } catch (e: Throwable) { lastError = e; return@repeat }
            }
            if (spec != chain.last()) next(Activity.Kind.NOTICE, "${profile.label} failed (${lastError?.message?.take(100)}); falling back.")
        }
        next(Activity.Kind.ERROR, "Model call failed: ${lastError?.message ?: "no usable model"}")
        return null
    }

    private suspend fun stream(adapter: Adapter, profile: Profile, model: String, cfg: Settings): Pair<String, ModelTurn> {
        val origin = "${profile.id}|$model"
        val specs = registry.specs(tools)
        val req = ModelRequest(
            model = model, system = session.meta.systemPrompt, messages = session.messages, tools = specs,
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
        val c = turn.usage.cost(profile.price)
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
        override suspend fun ask(question: String, options: List<String>): String {
            val q = Question(question, options, CompletableDeferred())
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
        val input = use["input"] as? JsonObject ?: obj()
        val aid = next(Activity.Kind.TOOL, tool = name, input = input.compact(), status = Activity.Status.RUNNING)
        val t0 = System.currentTimeMillis()
        fun result(r: ToolResult, status: Activity.Status): JsonObject {
            update(aid) { it.copy(status = status, summary = r.summary, images = r.images, ms = System.currentTimeMillis() - t0, progress = "") }
            return obj("type" to "tool_result", "tool_use_id" to id, "content" to r.blocks(), "is_error" to if (r.isError) true else null)
        }
        val tool = byName[name] ?: return result(ToolResult.error("unknown tool $name"), Activity.Status.FAILED)
        if (input["_invalid_json"] != null || truncated && input.isEmpty())
            return result(ToolResult.error("your tool input was cut off or invalid JSON — send it again (smaller, if it was large)"), Activity.Status.FAILED)
        validate(tool, input)?.let { return result(ToolResult.error(it), Activity.Status.FAILED) }

        when (if (name in sessionAllowed) Policy.ALLOW else registry.policy(tool, cfg.approval)) {
            Policy.DENY -> return result(ToolResult.error("$name is disabled by policy"), Activity.Status.DENIED)
            Policy.ASK -> {
                val req = ApprovalRequest(name, input.compact(), CompletableDeferred())
                approval.value = req
                val (ok, always) = try { req.answer.await() } finally { approval.value = null }
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
            update(aid) { it.copy(progress = "hook: $h") }
            val hr = runCatching { withTimeout(ht.timeoutMs) { ht.run(ctx(aid), obj()) } }.getOrElse { ToolResult.error(it.message ?: "hook failed") }
            r = r.copy(text = r.text + "\n\n[hook $h]\n" + hr.text, images = r.images + hr.images)
        }
        todos.value = state.todos
        return result(r, if (r.isError) Activity.Status.FAILED else Activity.Status.DONE)
    }

    /** Client-side schema check: required keys present, no unknown keys, basic types. */
    private fun validate(tool: Tool, input: JsonObject): String? {
        val props = tool.schema["properties"] as? JsonObject ?: return null
        val required = (tool.schema["required"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList()
        val missing = required.filter { it !in input }
        if (missing.isNotEmpty()) return "missing required input: ${missing.joinToString()}"
        val unknown = input.keys.filter { it !in props }
        if (unknown.isNotEmpty() && tool.schema["additionalProperties"]?.toString() == "false") return "unknown input: ${unknown.joinToString()}"
        return null
    }

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
        suspend fun headless(
            project: Project, sessionsRoot: File, providers: Providers, registry: ToolRegistry, tools: List<Tool>,
            settings: SettingsStore, systemPrompt: String, task: String, role: String,
        ): Pair<String, Usage> {
            val s = Session.create(File(sessionsRoot, ".sub").apply { mkdirs() }, project.name, systemPrompt)
            val loop = AgentLoop(project, s, providers, registry, tools, settings, role)
            loop.send(task)
            val text = s.messages.lastOrNull { it.role == "assistant" }?.content
                ?.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }?.joinToString("\n")
            return (text ?: "(no answer)") to loop.usage.value
        }
    }
}
