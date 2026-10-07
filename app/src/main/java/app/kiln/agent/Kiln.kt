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
    private val providers: Providers,
    val settings: SettingsStore,
) {
    private val classIndex = ClassIndex(toolchain)

    private fun builtins(): List<Tool> = listOf(
        ListDirTool(), GlobTool(), GrepTool(), ReadFileTool(), WriteFileTool(), EditFileTool(), MultiEditTool(),
        MoveTool(), DeleteTool(), ReadOutputTool(),
        ProjectInfoTool(), SetAppMetaTool(), CheckTool(builds), BuildTool(builds), CleanTool(builds),
        InstallTool(warden, device), RunAppTool(builds, warden, device), LaunchTool(warden, device),
        StopAppTool(warden, device), ClearDataTool(warden, device), GrantPermissionTool(warden, device),
        LogcatTool(warden, device), LastCrashTool(warden, device), ScreenshotTool(warden, device),
        UiTreeTool(warden, device), TapTool(warden, device), TypeTool(warden, device), SwipeTool(warden, device),
        KeyTool(warden, device), WaitForTool(warden, device), DumpsysTool(warden, device), ShellTool(warden, device),
        SdkLookupTool(classIndex), KitDocsTool(toolchain), WebFetchTool(),
        TodoTool(), AskUserTool(), CheckpointTool(), RestoreTool(), MemoryTool(),
    )

    /** Tools for [project]: built-ins + command tools (global and project) + connected MCP servers. */
    suspend fun tools(project: Project): Pair<ToolRegistry, List<Tool>> {
        val cfg = settings.value.merged(project.dir)
        val extra = mutableListOf<Tool>()
        extra += CommandTool.load(listOf(File(paths.files, "tools.d"), File(project.kilnDir, "tools.d")), warden)
        for (srv in cfg.mcpServers.filter { it.enabled }) runCatching { extra += McpClient(srv).connect() }
        val base = builtins()
        val registry = ToolRegistry(base)
        val brokerReady = warden.status() == Warden.Status.READY
        var tools = registry.assemble(extra, cfg.disabledTools, brokerReady)
        // The subagent sees only read-only tools.
        val readOnly = tools.filter { Trait.READ_ONLY in it.traits }
        tools = (tools + SubagentTool(this, project, registry, readOnly)).sortedBy { it.name }
        ToolRegistry.validateNames(tools)
        return registry to tools
    }

    fun systemPrompt(project: Project) =
        SystemPrompt.build(project, toolchain.kitApi(), warden.status() == Warden.Status.READY)

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

    suspend fun subTask(project: Project, registry: ToolRegistry, tools: List<Tool>, task: String) =
        AgentLoop.headless(project, paths.sessions, providers, registry, tools, settings,
            "You are a focused investigator helping an Android engineer. Use the read-only tools to answer the task " +
                "precisely and briefly; report facts with file:line references. Do not speculate.\n\n" +
                "Kit reference:\n" + toolchain.kitApi(), task, role = "subagent")
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
        val (answer, usage) = kiln.subTask(project, registry, readOnly, task)
        return ToolResult.ok(ctx.spill(answer), "subagent: ${usage.output} tokens out")
    }
}
