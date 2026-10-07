package app.kiln.ui

import app.kiln.llm.Providers
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.Icons
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kiln.Graph
import app.kiln.agent.Evals
import app.kiln.llm.AuthStyle
import app.kiln.llm.Profile
import app.kiln.llm.Protocol
import app.kiln.llm.RoleBinding
import app.kiln.mcp.McpServer
import app.kiln.toolchain.Toolchain
import app.kiln.tools.McpServerConfig
import app.kiln.ui.theme.N
import app.kiln.ui.theme.T
import app.kiln.ui.theme.vCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(vm: KilnVM, onClose: () -> Unit) {
    var editing by remember { mutableStateOf<Profile?>(null) }
    editing?.let { androidx.activity.compose.BackHandler { editing = null }; ProfileEditor(it) { editing = null }; return }
    Column(Modifier.fillMaxSize()) {
    KTopBar("Settings", onBack = onClose)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ToolchainCard(vm)
        ModelsCard { editing = it }
        LimitsCard()
        ToolsCard()
        McpCard()
        EvalsCard()
    }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().vCard().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), style = T.kicker); content()
    }

@Composable
private fun ToolchainCard(vm: KilnVM) {
    val state by vm.toolchain.collectAsStateWithLifecycle()
    // Bundled with the app and set up on launch: nothing to import.
    Section("Toolchain") {
        when (val s = state) {
            is Toolchain.State.Ready -> Text("Built in — JDK 21, Kotlin 2.4.20 (Compose), R8, aapt2, app kit. Set ${s.version}.", style = T.bodySmall)
            is Toolchain.State.Installing -> {
                Text("${s.step}…", style = T.bodySmall)
                LinearProgressIndicator(progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f }, color = N.accent, modifier = Modifier.fillMaxWidth())
            }
            is Toolchain.State.Failed -> {
                Text("Setup didn't finish: ${s.message}", style = T.bodySmall.copy(color = N.danger))
                KButton("Try again", Tone.Accent) { vm.setupToolchain() }
            }
            Toolchain.State.Missing -> Text("Setting up…", style = T.bodySmall)
        }
    }
}

@Composable
private fun ModelsCard(onEdit: (Profile) -> Unit) {
    var roles by remember { mutableStateOf(Graph.providers.roles) }
    val profiles = Graph.providers.profiles
    Section("Models") {
        listOf("agent" to "Main agent", "subagent" to "Subagent (cheap helper)").forEach { (role, label) ->
            val b = roles[role] ?: RoleBinding("anthropic", "claude-opus-5-5")
            var model by remember(role) { mutableStateOf(b.model) }
            Text(label, style = T.subtitle)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                profiles.forEach { p ->
                    KTag(p.id, if (p.id == b.profile) Tone.Accent else Tone.Neutral, Modifier.clickable {
                        Graph.providers.setRole(role, b.copy(profile = p.id, model = p.models.firstOrNull() ?: model)); roles = Graph.providers.roles
                        model = roles[role]!!.model
                    })
                }
            }
            KField("model", model, { model = it; Graph.providers.setRole(role, b.copy(model = it)); roles = Graph.providers.roles }, mono = true)
            // Keep the raw text: re-rendering the parsed list ate the comma before a second entry could be typed.
            var fallbacks by remember(role) { mutableStateOf(b.fallback.joinToString(", ")) }
            KField("fallbacks (profile:model, comma-separated)", fallbacks, { s ->
                fallbacks = s
                Graph.providers.setRole(role, b.copy(fallback = s.split(",").map { it.trim() }.filter { it.isNotEmpty() })); roles = Graph.providers.roles
            }, mono = true)
        }
        Text("PROVIDERS", style = T.overline)
        profiles.forEach { p ->
            Row(Modifier.fillMaxWidth().clickable { onEdit(p) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(if (Graph.providers.key(p.id) != null || p.auth == AuthStyle.NONE) N.ok else N.neutral600); Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.label, style = T.subtitle)
                    Text("${p.protocol.name.lowercase().replace('_', '-')} · ${p.baseUrl}", style = T.monoSmall)
                }
            }
        }
        // Presets first (Anthropic, OpenAI, OpenRouter, On Device AI, Telecode…), then a blank one.
        Box {
            var pick by remember { mutableStateOf(false) }
            KButton("Add provider") { pick = true }
            DropdownMenu(pick, { pick = false }, containerColor = N.surfaceHi) {
                Providers.PRESETS.filter { pr -> profiles.none { it.id == pr.id } }.forEach { pr ->
                    DropdownMenuItem(text = { Column { Text(pr.label, style = T.body); Text(pr.baseUrl, style = T.monoSmall) } },
                        onClick = { pick = false; onEdit(pr) })
                }
                DropdownMenuItem(text = { Text("Custom…", style = T.body.copy(color = N.accent)) }, onClick = {
                    pick = false
                    onEdit(Profile("custom-${System.currentTimeMillis() % 100000}", "Custom", Protocol.OPENAI_CHAT, "https://", AuthStyle.BEARER))
                })
            }
        }
    }
}

@Composable
private fun ProfileEditor(start: Profile, onDone: () -> Unit) {
    var p by remember { mutableStateOf(start) }
    var key by remember { mutableStateOf("") }
    var models by remember { mutableStateOf(start.models.joinToString(", ")) }
    var report by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun current() = p.copy(models = models.split(",").map { it.trim() }.filter { it.isNotEmpty() })
    val exists = Graph.providers.profiles.any { it.id == start.id }
    Column(Modifier.fillMaxSize()) {
    KTopBar(p.label.ifBlank { "Provider" }, subtitle = if (exists) "Edit provider" else "New provider", onBack = onDone) {
        IconBtn(Icons.Rounded.Check, "Save", tint = N.accent) {
            Graph.providers.save(current()); if (key.isNotBlank()) Graph.providers.setKey(p.id, key.trim()); onDone()
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KField("Label", p.label, { p = p.copy(label = it) })
        Text("Protocol", style = T.label)
        SegTabs(Protocol.entries.map { when (it) { Protocol.ANTHROPIC -> "Anthropic"; Protocol.OPENAI_CHAT -> "Chat API"; Protocol.OPENAI_RESPONSES -> "Responses" } to "" }, p.protocol.ordinal) { p = p.copy(protocol = Protocol.entries[it]) }
        KField("Base URL (with or without /v1)", p.baseUrl, { p = p.copy(baseUrl = it) }, mono = true)
        Text("Auth", style = T.label)
        SegTabs(AuthStyle.entries.map { when (it) { AuthStyle.X_API_KEY -> "x-api-key"; AuthStyle.BEARER -> "Bearer"; AuthStyle.NONE -> "None" } to "" }, p.auth.ordinal) { p = p.copy(auth = AuthStyle.entries[it]) }
        KField(if (Graph.providers.key(p.id) != null) "API key (saved — type to replace)" else "API key", key, { key = it }, mono = true, hint = "kept in the Android Keystore")
        KField("Models (comma-separated)", models, { models = it }, mono = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KButton("Pin certificate") {
                scope.launch {
                    report = runCatching { withContext(Dispatchers.IO) { Graph.providers.fetchCertificate(p.baseUrl) } }
                        .fold({ (sha, _) -> p = p.copy(pinnedCertSha256 = sha); "Pinned SHA-256 $sha — check it matches the server's screen." },
                              { "Could not fetch /certificate: ${it.message}" })
                }
            }
            KButton(if (busy) "Testing…" else "Test connection", Tone.Accent, enabled = !busy) {
                Graph.providers.save(current()); if (key.isNotBlank()) Graph.providers.setKey(p.id, key.trim())
                busy = true
                scope.launch {
                    val model = current().models.firstOrNull() ?: ""
                    report = runCatching { withContext(Dispatchers.IO) { Graph.providers.probe(current(), model) } }.fold({ r ->
                        p = current().copy(caps = r.caps, models = (current().models + r.models.take(30)).distinct(),
                            modelPrices = current().modelPrices + r.prices.filterKeys { k -> k in current().models || k in r.models.take(30) })
                        models = p.models.joinToString(", "); Graph.providers.save(p)
                        r.notes.joinToString("\n")
                    }, { "Probe failed: ${it.message}" })
                    busy = false
                }
            }
        }
        p.pinnedCertSha256?.let { Text("pinned: $it", style = T.monoSmall) }
        if (report.isNotBlank()) Text(report, style = T.mono)
        Text("Capabilities: " + listOfNotNull(
            "tools".takeIf { p.caps.tools }, "parallel".takeIf { p.caps.parallelTools }, "vision".takeIf { p.caps.vision },
            "caching".takeIf { p.caps.caching }, "thinking".takeIf { p.caps.thinking }, "effort".takeIf { p.caps.effort },
            "compaction".takeIf { p.caps.compaction }, "context-editing".takeIf { p.caps.contextEditing }).joinToString(", "), style = T.bodySmall)
        if (exists) KButton("Delete provider", Tone.Danger, Modifier.padding(top = 8.dp)) { Graph.providers.remove(p.id); onDone() }
    }
    }
}

@Composable
private fun LimitsCard() {
    var s by remember { mutableStateOf(Graph.settings.value) }
    fun save(f: (app.kiln.agent.Settings) -> app.kiln.agent.Settings) { Graph.settings.update(f); s = Graph.settings.value }
    Section("Limits") {
        NumField("Max steps per request", s.maxSteps.toString(), { it.toIntOrNull()?.takeIf { n -> n > 0 } }) { n -> save { it.copy(maxSteps = n) } }
        NumField("Spending cap per chat (USD)", s.sessionUsd.toString(), { it.toDoubleOrNull()?.takeIf { n -> n >= 0 } }) { n -> save { it.copy(sessionUsd = n) } }
        NumField("Spending cap per day (USD)", s.dailyUsd.toString(), { it.toDoubleOrNull()?.takeIf { n -> n >= 0 } }) { n -> save { it.copy(dailyUsd = n) } }
        Text("Spent today: $" + "%.3f".format(Graph.settings.spentToday()), style = T.bodySmall)
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text("Test apps in the background", style = T.body)
                Text("The agent runs and taps the app on a hidden display, so your screen stays yours. Your Run button always uses the real screen.",
                    style = T.label)
            }
            androidx.compose.material3.Switch(s.backgroundTesting, { v -> save { it.copy(backgroundTesting = v) } },
                colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = N.accent))
        }
        Text("Effort (main agent)", style = T.label)
        val levels = listOf("low", "medium", "high", "xhigh", "max")
        SegTabs(levels.map { it to "" }, levels.indexOf(s.effort).coerceAtLeast(0)) { i -> save { it.copy(effort = levels[i]) } }
    }
}

@Composable
private fun ToolsCard() {
    var s by remember { mutableStateOf(Graph.settings.value) }
    Section("Tool approval") {
        Text("Destructive tools ask by default. Change any tool's policy here.", style = T.bodySmall)
        listOf("shell", "delete", "clear_data", "restore").forEach { tool ->
            val cur = s.approval[tool] ?: "ask"
            Text(tool, style = T.mono.copy(color = N.text))
            SegTabs(listOf("allow" to "", "ask" to "", "deny" to ""), listOf("allow", "ask", "deny").indexOf(cur)) { i ->
                Graph.settings.update { it.copy(approval = it.approval + (tool to listOf("allow", "ask", "deny")[i])) }; s = Graph.settings.value
            }
        }
    }
}

@Composable
private fun McpCard() {
    var s by remember { mutableStateOf(Graph.settings.value) }
    var name by remember { mutableStateOf("") }; var url by remember { mutableStateOf("") }; var token by remember { mutableStateOf("") }
    val status by McpServer.status.collectAsStateWithLifecycle()
    Section("MCP") {
        Text("Serve Kiln's tools over MCP on the tailnet, so Claude Code or Codex on a PC can build on this phone.", style = T.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            KButton(if (s.mcpServe) "Stop server" else "Start server", if (s.mcpServe) Tone.Danger else Tone.Accent) {
                Graph.settings.update { it.copy(mcpServe = !it.mcpServe) }; s = Graph.settings.value
                if (s.mcpServe) McpServer.start(s.mcpPort) else McpServer.stop()
            }
            Spacer(Modifier.width(8.dp)); Text(status, style = T.monoSmall)
        }
        Text("CONNECTED SERVERS (tools appear in new chats)", style = T.overline)
        s.mcpServers.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${m.name}  ${m.url}", style = T.monoSmall, modifier = Modifier.weight(1f))
                KButton("Remove", Tone.Danger) { Graph.settings.update { it.copy(mcpServers = it.mcpServers - m) }; s = Graph.settings.value }
            }
        }
        KField("Name", name, { name = it }); KField("URL", url, { url = it }, mono = true); KField("Bearer token (optional)", token, { token = it }, mono = true)
        KButton("Add server", enabled = name.isNotBlank() && url.isNotBlank()) {
            Graph.settings.update { it.copy(mcpServers = it.mcpServers + McpServerConfig(name.trim(), url.trim(), token.trim().ifBlank { null })) }
            s = Graph.settings.value; name = ""; url = ""; token = ""
        }
    }
}

@Composable
private fun EvalsCard() {
    val state by Evals.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Section("Evals") {
        Text("Build a fixed set of apps from scratch and score them: built, runs without crashing, steps, cost, time. Run after changing models or settings. Uses real API spend.", style = T.bodySmall)
        KButton(if (state.running) "Running ${state.done}/${state.total}…" else "Run evals", Tone.Accent, enabled = !state.running) {
            scope.launch(Dispatchers.IO) { Evals.run() }
        }
        if (state.report.isNotBlank()) Text(state.report, style = T.mono)
    }
}

/**
 * A number field that keeps what the user is typing (it can be emptied and retyped) and saves
 * only values that parse — writing the parsed value back made "40" impossible to change to "25".
 */
@Composable
private fun <T> NumField(label: String, initial: String, parse: (String) -> T?, onValid: (T) -> Unit) {
    var raw by remember { mutableStateOf(initial) }
    KField(label, raw, { v -> raw = v; parse(v.trim())?.let(onValid) })
}
