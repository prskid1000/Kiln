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
        KeyTool(warden, device), WaitForTool(warden, device), DumpsysTool(warden, device), ShellTool(warden, device),
        SdkLookupTool(classIndex), KitDocsTool(toolchain, skills), WebFetchTool(), app.kiln.tools.MakeGraphicTool(),
        app.kiln.tools.LoadSkillTool(skills), app.kiln.tools.ProposeRuleTool(), app.kiln.tools.SecurityCheckTool(),
        app.kiln.tools.CompareScreenTool(warden, device), app.kiln.tools.UiCheckTool(warden, device), app.kiln.tools.SaveScreenshotTool(warden, device),
        TodoTool(), AskUserTool(), CheckpointTool(), RestoreTool(), MemoryTool(),
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
        // The subagent sees only read-only tools.
        // The subagent runs headless: nobody can answer its questions or approvals.
        val readOnly = tools.filter { Trait.READ_ONLY in it.traits && it.name != "ask_user" }
        // QA: a fresh agent that can only look at and use the app (no editing, no building).
        val qaTools = tools.filter { it.name in QA_TOOLS }
        val agentDevice = if (settings.value.backgroundTesting) testDevice else device
        val qa = app.kiln.tools.QaCheckTool({ criteria -> qaTask(project, registry, qaTools, criteria) }, agentDevice)
        val deferred = tools.filter { it.deferred }
        tools = (tools + SubagentTool(this, project, registry, readOnly) + qa +
            listOfNotNull(deferred.takeIf { it.isNotEmpty() }?.let { app.kiln.tools.ToolSearchTool(it) })).sortedBy { it.name }
        ToolRegistry.validateNames(tools)
        return registry to tools
    }

    fun systemPrompt(project: Project) =
        SystemPrompt.build(project, toolchain.kitApi(), warden.status() == Warden.Status.READY, skills.index(), toolchain.kitIndex())

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

    suspend fun qaTask(project: Project, registry: ToolRegistry, tools: List<Tool>, criteria: String) =
        AgentLoop.headless(project, paths.sessions, providers, registry, tools, settings, QA_PROMPT,
            "Done criteria:\n$criteria\n\nTest every criterion on the device now.", role = "subagent",
            finished = Regex("VERDICT:\\s*(PASS|FAIL)"),
            unfinished = "You stopped before finishing. Use the tools now to test every criterion on the device, then end " +
                "with one line per criterion and the final line VERDICT: PASS or VERDICT: FAIL.")

    suspend fun subTask(project: Project, registry: ToolRegistry, tools: List<Tool>, task: String) =
        AgentLoop.headless(project, paths.sessions, providers, registry, tools, settings,
            "You are a focused investigator helping an Android engineer. Use the read-only tools to answer the task " +
                "precisely and briefly; report facts with file:line references. Do not speculate.\n\n" +
                "Kit reference:\n" + toolchain.kitApi() + "\n\nKit index:\n" + toolchain.kitIndex(), task, role = "subagent")
}

/** Delegate a self-contained investigation to a cheaper model with read-only tools (SPEC §5). */
class SubagentTool(private val kiln: Kiln, private val project: Project, private val registry: ToolRegistry,
                   private val readOnly: List<Tool>) : Tool {
    override val name = "subagent"
    override val description = "Hand a self-contained, read-only investigation to a helper (a cheaper model) and get back a short report — e.g. \"find every screen that reads the todos store and summarise how\". Keeps long reading out of your context."
    override val schema = schema { str("task", "What to investigate and what to report back.") }
    override val traits = setOf(Trait.READ_ONLY, Trait.LONG_RUNNING)
    override val timeoutMs = 900_000L
    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val task = input.str("task") ?: return ToolResult.error("missing task")
        ctx.progress("subagent working")
        val (answer, usage, usd) = kiln.subTask(project, registry, readOnly, task)
        ctx.addCost(usd)
        return ToolResult.ok(ctx.spill(answer), "subagent: ${usage.output} tokens out")
    }
}

/** More MCP tools than this are deferred behind tool_search. */
private const val DEFER_MCP_OVER = 8

/** What the QA agent may use: look at and operate the app, read the code, never change it. */
private val QA_TOOLS = setOf("launch", "screenshot", "ui_tree", "tap", "type_text", "swipe", "press_key", "wait_for",
    "logcat", "last_crash", "ui_check", "read_file", "list_dir", "grep")

private const val QA_PROMPT = "You are a strict QA tester for an Android app on this phone. You did not build it. " +
    "Start with launch, then exercise the app to check each done criterion: tap by visible label, type, swipe, wait_for, " +
    "and read the screen (ui_tree / screenshot). A criterion passes only if you observed it. Also note crashes (last_crash) " +
    "and anything broken you see on the way. Reply with one line per criterion: PASS or FAIL — criterion — what you saw. " +
    "End with exactly one line: VERDICT: PASS (every criterion passed) or VERDICT: FAIL."
