package app.kiln.agent

import app.kiln.build.BuildEngine
import app.kiln.build.Project
import app.kiln.core.Paths
import app.kiln.core.str
import app.kiln.device.Device
import app.kiln.device.Warden
import app.kiln.llm.Providers
import app.kiln.toolchain.Toolchain
import app.kiln.tools.AskUserTool
import app.kiln.tools.BuildTool
import app.kiln.tools.CheckTool
import app.kiln.tools.CheckpointTool
import app.kiln.tools.ClassIndex
import app.kiln.tools.CleanTool
import app.kiln.tools.ClearDataTool
import app.kiln.tools.CommandTool
import app.kiln.tools.DeleteTool
import app.kiln.tools.DumpsysTool
import app.kiln.tools.EditFileTool
import app.kiln.tools.GlobTool
import app.kiln.tools.GrantPermissionTool
import app.kiln.tools.GrepTool
import app.kiln.tools.InstallTool
import app.kiln.tools.KeyTool
import app.kiln.tools.KitDocsTool
import app.kiln.tools.LastCrashTool
import app.kiln.tools.LaunchTool
import app.kiln.tools.ListDirTool
import app.kiln.tools.LogcatTool
import app.kiln.tools.McpClient
import app.kiln.tools.MemoryTool
import app.kiln.tools.MoveTool
import app.kiln.tools.MultiEditTool
import app.kiln.tools.ProjectInfoTool
import app.kiln.tools.ReadFileTool
import app.kiln.tools.ReadOutputTool
import app.kiln.tools.RestoreTool
import app.kiln.tools.RunAppTool
import app.kiln.tools.ScreenshotTool
import app.kiln.tools.SdkLookupTool
import app.kiln.tools.SetAppMetaTool
import app.kiln.tools.ShellTool
import app.kiln.tools.StopAppTool
import app.kiln.tools.SwipeTool
import app.kiln.tools.TapTool
import app.kiln.tools.TodoTool
import app.kiln.tools.Tool
import app.kiln.tools.ToolContext
import app.kiln.tools.ToolRegistry
import app.kiln.tools.ToolResult
import app.kiln.tools.Trait
import app.kiln.tools.TypeTool
import app.kiln.tools.UiTreeTool
import app.kiln.tools.WaitForTool
import app.kiln.tools.WebFetchTool
import app.kiln.tools.WriteFileTool
import app.kiln.tools.schema
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Assembles a session: tools (built-in + command tools + MCP), registry,
 * frozen system prompt, and the loop.
 */
class Kiln(
    private val paths: Paths,
    private val toolchain: Toolchain,
    private val builds: BuildEngine,
    private val warden: Warden,
    private val device: Device,
    private val testDevice: Device,
    private val providers: Providers,
    val settings: SettingsStore,
    val skills: app.kiln.tools.Skills,
) {
    private val classIndex = ClassIndex(toolchain)

    private fun builtins(): List<Tool> {
        // The agent tests on a hidden display unless the user turned that off; the user's own
        // Run button always uses the real screen.
        val device = if (settings.value.backgroundTesting) testDevice else device
        return listOf(
        ListDirTool(), GlobTool(), GrepTool(), ReadFileTool(), WriteFileTool(), EditFileTool(), MultiEditTool(),
        MoveTool(), DeleteTool(), ReadOutputTool(),
        ProjectInfoTool(), SetAppMetaTool(), CheckTool(builds, classIndex), BuildTool(builds, classIndex), CleanTool(builds),
        InstallTool(warden, device), RunAppTool(builds, warden, device, classIndex), LaunchTool(warden, device),
        StopAppTool(warden, device), ClearDataTool(warden, device), GrantPermissionTool(warden, device),
        LogcatTool(warden, device), LastCrashTool(warden, device), ScreenshotTool(warden, device),
        UiTreeTool(warden, device), TapTool(warden, device), TypeTool(warden, device), SwipeTool(warden, device),
        KeyTool(warden, device), WaitForTool(warden, device), app.kiln.tools.TestFlowTool(warden, device), DumpsysTool(warden, device), ShellTool(warden, device),
        SdkLookupTool(classIndex), KitDocsTool(toolchain, skills), WebFetchTool(), app.kiln.tools.MakeGraphicTool(),
        app.kiln.tools.LoadSkillTool(skills), app.kiln.tools.ProposeRuleTool(), app.kiln.tools.SecurityCheckTool(),
        app.kiln.tools.CompareScreenTool(warden, device), app.kiln.tools.UiCheckTool(warden, device), app.kiln.tools.SaveScreenshotTool(warden, device),
        TodoTool(), AskUserTool(), app.kiln.tools.ChooseLookTool(), CheckpointTool(), RestoreTool(), MemoryTool(),
        )
    }

    /** Tools for [project]: built-ins + command tools (global and project) + connected MCP servers. */
    suspend fun tools(project: Project): Pair<ToolRegistry, List<Tool>> {
        val cfg = settings.value.merged(project.dir)
        val extra = mutableListOf<Tool>()
        extra += CommandTool.load(listOf(File(paths.files, "tools.d")), warden) + CommandTool.load(listOf(File(project.kilnDir, "tools.d")), warden, trusted = false)
        for (srv in cfg.mcpServers.filter { it.enabled }) runCatching { extra += McpClient(srv).connect() }
        // Many MCP tools would bloat every request: hold them back behind tool_search.
        val mcp = extra.filterIsInstance<app.kiln.tools.McpTool>()
        if (mcp.size > DEFER_MCP_OVER) mcp.forEach { it.deferred = true }
        val base = builtins()
        val registry = ToolRegistry(base)
        val brokerReady = warden.status() == Warden.Status.READY
        var tools = registry.assemble(extra, cfg.disabledTools, brokerReady)
        // Helper agents (QA, explore, reviewer, project agents) all run through runAgent with these tools to pick from.
        val available = tools
        val specs = Agents.all(project)
        val agentDevice = if (settings.value.backgroundTesting) testDevice else device
        val qa = app.kiln.tools.QaCheckTool({ criteria, ctx, onStep ->
            runAgent(project, registry, available, Agents.QA, "Done criteria:\n$criteria\n\nTest the criteria on the device now.", ctx, onStep)
        }, agentDevice, prepare = { ctx ->
            // QA always tests the latest code: build (cached when nothing changed) and install, so the agent can go
            // straight from a fix to qa_check without a run_app in between.
            ctx.progress("building and installing the latest code for QA")
            val (r, fixed) = app.kiln.tools.buildFixingImports(builds, classIndex, ctx, checkOnly = false)
            // QA's build is the latest one: the stop guard and `install` must see it, not an older failure or APK.
            ctx.state.lastBuild = r
            when {
                !r.ok -> fixed + "The project doesn't build, so QA didn't run. Fix these first:\n" +
                    r.errors.take(8).joinToString("\n") { "  ${it.file}:${it.line} ${it.message}" }
                !agentDevice.install(File(r.apk!!)).out.contains("Success") -> "Couldn't install the app for QA — try run_app to see why."
                else -> null
            }
        })
        val deferred = tools.filter { it.deferred }
        // qa_check only with the QA agent on; both helpers obey disabledTools like every other tool.
        tools = (tools + SubagentTool(this, project, registry, available, specs) + listOfNotNull(qa.takeIf { cfg.qaAgent && brokerReady }) +
            listOfNotNull(deferred.takeIf { it.isNotEmpty() }?.let { app.kiln.tools.ToolSearchTool(it) }))
            .filter { it.name !in cfg.disabledTools }.sortedBy { it.name }
        ToolRegistry.validateNames(tools)
        return registry to tools
    }

    fun systemPrompt(project: Project) =
        SystemPrompt.build(project, toolchain.kitApi(), warden.status() == Warden.Status.READY, skills.index(), toolchain.kitIndex(),
            qaAgent = settings.value.merged(project.dir).qaAgent && warden.status() == Warden.Status.READY)

    suspend fun newSession(project: Project): AgentLoop {
        val s = Session.create(paths.sessions, project.name, systemPrompt(project))
        val (reg, tools) = tools(project)
        return AgentLoop(project, s, providers, reg, tools, settings)
    }

    suspend fun openSession(project: Project, id: String): AgentLoop? {
        val s = Session.open(File(paths.sessions, id)) ?: return null
        val (reg, tools) = tools(project)
        return AgentLoop(project, s, providers, reg, tools, settings)
    }

    /**
     * Run a helper agent of kind [spec] on [task]: a headless loop with the spec's tools (from [available]),
     * its steps streamed into the chat under the spec's tag via [ctx]. Every kind of helper runs through here.
     */
    suspend fun runAgent(project: Project, registry: ToolRegistry, available: List<Tool>, spec: AgentSpec, task: String,
                         ctx: ToolContext?, onStep: ((String) -> Unit)? = null): Triple<String, app.kiln.llm.Usage, Double> {
        // Helpers can't ask the user (nobody answers a headless loop) or start other helpers.
        val tools = available.filter { t ->
            t.name !in HELPER_TOOLS && t.name != "ask_user" && t.name != "choose_look" &&
                (spec.tools?.contains(t.name) ?: (Trait.READ_ONLY in t.traits))
        }
        val prompt = spec.prompt + if (spec.kitDocs) "\n\nKit reference:\n" + toolchain.kitApi() + "\n\nKit index:\n" + toolchain.kitIndex() else ""
        return AgentLoop.headless(project, paths.sessions, providers, registry, tools, settings, prompt, task, role = "subagent",
            finished = spec.finished, unfinished = spec.unfinished, onStep = onStep,
            onFeed = ctx?.let { c -> { feed -> c.children(feed.map { it.copy(agent = spec.tag) }) } })
    }
}

/** Spawn a helper agent of a given type: built-ins (explore, reviewer, …) and the project's own (.kiln/agents). */
class SubagentTool(private val kiln: Kiln, private val project: Project, private val registry: ToolRegistry,
                   private val available: List<Tool>, private val specs: List<AgentSpec>) : Tool {
    override val name = "subagent"
    override val description = "Hand a self-contained task to a helper agent and get back its report; its steps show in the chat. " +
        "Keeps long reading out of your context. Types: " + specs.filter { it.name != "qa" }.joinToString("; ") { "${it.name} — ${it.description}" } +
        ". (To test the app against done criteria use qa_check.)"
    override val schema = schema {
        str("type", "Which kind of helper.", enum = specs.filter { it.name != "qa" }.map { it.name })
        str("task", "What to do and what to report back.")
    }
    override val traits = setOf(Trait.READ_ONLY, Trait.LONG_RUNNING)
    override val timeoutMs = 900_000L
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val task = input.str("task") ?: return ToolResult.error("missing task")
        val spec = specs.firstOrNull { it.name == (input.str("type") ?: "explore") }
            ?: return ToolResult.error("no helper type '${input.str("type")}' — use one of: ${specs.joinToString { it.name }}")
        ctx.progress("${spec.tag} agent working")
        val (answer, usage, usd) = kiln.runAgent(project, registry, available, spec, task, ctx)
        ctx.addCost(usd)
        return ToolResult.ok(ctx.spill(answer), "${spec.tag}: ${usage.output} tokens out")
    }
}

/** More MCP tools than this are deferred behind tool_search. */
private const val DEFER_MCP_OVER = 8

/** Tools that start helpers: never given to a helper. */
private val HELPER_TOOLS = setOf("subagent", "qa_check")
