package app.kiln.agent

import app.kiln.core.KJ
import app.kiln.tools.McpServerConfig
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDate

/** Global settings (files/settings.json). A project's .kiln/config.json may override any field. */
@Serializable
data class Settings(
    val maxSteps: Int = 80,
    val maxRetries: Int = 3,
    val taskBudgetTokens: Int = 400_000,
    val sessionUsd: Double = 3.0,
    val dailyUsd: Double = 10.0,
    val effort: String = "high",
    val subagentEffort: String = "medium",
    /** tool name → allow | ask | deny (defaults come from traits). */
    val approval: Map<String, String> = emptyMap(),
    val disabledTools: Set<String> = emptySet(),
    /** "postTool:<tool>" → tools (no input) to run after it, output appended to its result. */
    val hooks: Map<String, List<String>> = emptyMap(),
    val mcpServers: List<McpServerConfig> = emptyList(),
    /** Kiln's own MCP server (SPEC §8.7). */
    val mcpServe: Boolean = false,
    val mcpPort: Int = 8765,
    /** The agent runs and tests apps on an invisible display instead of taking over the screen. */
    val backgroundTesting: Boolean = true,
) {
    fun merged(project: File?): Settings {
        val f = project?.let { File(it, ".kiln/config.json") }?.takeIf { it.isFile } ?: return this
        return runCatching {
            val o = KJ.parseToJsonElement(f.readText()) as kotlinx.serialization.json.JsonObject
            val base = KJ.encodeToJsonElement(serializer(), this) as kotlinx.serialization.json.JsonObject
            KJ.decodeFromJsonElement(serializer(), kotlinx.serialization.json.JsonObject(base + o))
        }.getOrDefault(this)
    }
}

class SettingsStore(private val dir: File) {
    private val file = File(dir, "settings.json")
    private val spendFile = File(dir, "spend.json")

    var value: Settings = runCatching { KJ.decodeFromString(Settings.serializer(), file.readText()) }.getOrDefault(Settings())
        private set

    fun update(f: (Settings) -> Settings) {
        value = f(value)
        file.writeText(KJ.encodeToString(Settings.serializer(), value))
    }

    @Serializable private data class Spend(val day: String, val usd: Double)

    fun spentToday(): Double = runCatching { KJ.decodeFromString(Spend.serializer(), spendFile.readText()) }
        .getOrNull()?.takeIf { it.day == LocalDate.now().toString() }?.usd ?: 0.0

    fun addSpend(usd: Double) {
        if (usd <= 0) return
        spendFile.writeText(KJ.encodeToString(Spend.serializer(), Spend(LocalDate.now().toString(), spentToday() + usd)))
    }
}
