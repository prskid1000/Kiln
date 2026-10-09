package app.kiln.ui

import app.kiln.llm.Providers
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.rounded.Check
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Construction
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
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

/** A settings group: an icon tile, a title, what it's for, then its controls. */
@Composable
private fun Section(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String,
                    trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().vCard(N.shapeLg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(N.shapeMd).background(N.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(icon, null, tint = N.accent2, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = T.cardTitle)
                Text(subtitle, style = T.label)
            }
            trailing?.invoke()
        }
        content()
    }

/** A pill with a coloured dot: Ready, Running, Stopped… */
@Composable
private fun StatusPill(text: String, color: androidx.compose.ui.graphics.Color) =
    Row(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(color.copy(alpha = 0.14f))
        .padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(color, Modifier.size(7.dp)); Spacer(Modifier.width(6.dp)); Text(text, style = T.label.copy(color = color))
    }

/** One setting on a line: what it is (and why) on the left, its control on the right. */
@Composable
private fun SettingRow(title: String, desc: String? = null, control: @Composable () -> Unit) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = T.body)
            desc?.let { Text(it, style = T.label) }
        }
        control()
    }

@Composable
private fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) =
    androidx.compose.material3.Switch(on, onChange, colors = androidx.compose.material3.SwitchDefaults.colors(
        checkedTrackColor = N.accent, checkedThumbColor = N.text, uncheckedTrackColor = N.bg, uncheckedBorderColor = N.divider))

/** A small three-way choice that fits beside a label. */
@Composable
private fun MiniSeg(options: List<String>, selected: Int, onSelect: (Int) -> Unit) =
    Row(Modifier.clip(N.shapeMd).background(N.bg).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Text(o, style = T.label.copy(color = if (on) N.text else N.textMuted),
                modifier = Modifier.clip(N.shapeMd).background(if (on) N.accent.copy(alpha = 0.35f) else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(i) }.padding(horizontal = 12.dp, vertical = 7.dp))
        }
    }

@Composable
private fun ToolchainCard(vm: KilnVM) {
    val state by vm.toolchain.collectAsStateWithLifecycle()
    // Bundled with the app and set up on launch: nothing to import.
    Section(Icons.Rounded.Construction, "Toolchain", "Builds apps right here — bundled with Kiln", trailing = {
        when (state) {
            is Toolchain.State.Ready -> StatusPill("Ready", N.ok)
            is Toolchain.State.Failed -> StatusPill("Failed", N.danger)
            else -> StatusPill("Setting up", N.warn)
        }
    }) {
        when (val s = state) {
            is Toolchain.State.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("JDK 21", "Kotlin 2.4", "Compose", "R8", "aapt2").forEach { KTag(it) }
            }
            is Toolchain.State.Installing -> {
                Text("${s.step}…", style = T.bodySmall)
                LinearProgressIndicator(progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f }, color = N.accent,
                    trackColor = N.bg, modifier = Modifier.fillMaxWidth())
            }
            is Toolchain.State.Failed -> {
                Text(s.message, style = T.bodySmall.copy(color = N.danger))
                KButton("Try again", Tone.Accent) { vm.setupToolchain() }
            }
            Toolchain.State.Missing -> Text("Checking the build tools…", style = T.bodySmall)
        }
    }
}

@Composable
private fun ModelsCard(onEdit: (Profile) -> Unit) {
    var roles by remember { mutableStateOf(Graph.providers.roles) }
    val profiles = Graph.providers.profiles
    Section(Icons.Rounded.AutoAwesome, "Models", "Who writes the code, and who helps") {
        listOf("agent" to "Main agent", "subagent" to "Helper (cheaper, for research and QA)").forEach { (role, label) ->
            val b = roles[role] ?: RoleBinding("anthropic", "claude-opus-5-5")
            var model by remember(role) { mutableStateOf(b.model) }
            Column(Modifier.fillMaxWidth().clip(N.shapeMd).background(N.bg.copy(alpha = 0.6f)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = T.subtitle)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    profiles.forEach { p ->
                        KTag(p.label, if (p.id == b.profile) Tone.Accent else Tone.Neutral, Modifier.clickable {
                            Graph.providers.setRole(role, b.copy(profile = p.id, model = p.models.firstOrNull() ?: model)); roles = Graph.providers.roles
                            model = roles[role]!!.model
                        })
                    }
                }
                KField("", model, { model = it; Graph.providers.setRole(role, b.copy(model = it)); roles = Graph.providers.roles },
                    mono = true, hint = "model id")
            }
        }
        Text("PROVIDERS", style = T.overline)
        // Which providers have a key: a Keystore decrypt each, so read once off the main thread, not per recomposition.
        val withKey by androidx.compose.runtime.produceState(emptySet<String>(), profiles) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { profiles.filter { Graph.providers.key(it.id) != null }.map { it.id }.toSet() } }
        profiles.forEach { p ->
            Row(Modifier.fillMaxWidth().clip(N.shapeMd).clickable { onEdit(p) }.padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Dot(if (p.id in withKey || p.auth == AuthStyle.NONE) N.ok else N.neutral600); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.label, style = T.subtitle)
                    Text("${p.protocol.name.lowercase().replace('_', '-')} · ${p.baseUrl}", style = T.monoSmall)
                }
                androidx.compose.material3.Icon(Icons.Rounded.ChevronRight, null, tint = N.textMuted)
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
    // Read once, off the main thread (a Keystore decrypt ran on every keystroke).
    val keySaved by androidx.compose.runtime.produceState(false, p.id) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Graph.providers.key(p.id) != null } }
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
        KField(if (keySaved) "API key (saved — type to replace)" else "API key", key, { key = it }, mono = true, hint = "kept in the Android Keystore", secret = true)
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
    Section(Icons.Rounded.Tune, "Limits & behaviour", "Spent today: $" + "%.3f".format(Graph.settings.spentToday())) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { NumField("Max steps", s.maxSteps.toString(), { it.toIntOrNull()?.takeIf { n -> n > 0 } }) { n -> save { it.copy(maxSteps = n) } } }
            Box(Modifier.weight(1f)) { NumField("Per chat $", s.sessionUsd.toString(), { it.toDoubleOrNull()?.takeIf { n -> n >= 0 } }) { n -> save { it.copy(sessionUsd = n) } } }
            Box(Modifier.weight(1f)) { NumField("Per day $", s.dailyUsd.toString(), { it.toDoubleOrNull()?.takeIf { n -> n >= 0 } }) { n -> save { it.copy(dailyUsd = n) } } }
        }
        SettingRow("Test apps in the background", "The agent uses a hidden screen, so yours stays yours. Run always uses the real screen.") {
            Toggle(s.backgroundTesting) { v -> save { it.copy(backgroundTesting = v) } }
        }
        SettingRow("QA agent", "Before finishing, a separate agent tests the app and records it (a few minutes). Off: the agent checks its own work with test_flow.") {
            Toggle(s.qaAgent) { v -> save { it.copy(qaAgent = v) } }
        }
        Text("Effort (main agent)", style = T.label)
        val levels = listOf("low", "medium", "high", "xhigh", "max")
        SegTabs(levels.map { it to "" }, levels.indexOf(s.effort).coerceAtLeast(0)) { i -> save { it.copy(effort = levels[i]) } }
    }
}

@Composable
private fun ToolsCard() {
    var s by remember { mutableStateOf(Graph.settings.value) }
    val choices = listOf("allow", "ask", "deny")
    Section(Icons.Rounded.VerifiedUser, "Tool approval", "What the agent may do without asking") {
        listOf("shell" to "Run shell commands", "delete" to "Delete files", "clear_data" to "Clear the app's data",
            "restore" to "Restore a checkpoint").forEach { (tool, what) ->
            SettingRow(what, tool) {
                MiniSeg(choices, choices.indexOf(s.approval[tool] ?: "ask")) { i ->
                    Graph.settings.update { it.copy(approval = it.approval + (tool to choices[i])) }; s = Graph.settings.value
                }
            }
        }
    }
}

@Composable
private fun McpCard() {
    RemoteControlCard()
    ExtraToolsCard()
}

/** Kiln as an MCP server: a coding agent on a PC drives Kiln on this phone. */
@Composable
private fun RemoteControlCard() {
    var s by remember { mutableStateOf(Graph.settings.value) }
    var copied by remember { mutableStateOf(false) }
    val status by McpServer.status.collectAsStateWithLifecycle()
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    Section(Icons.Rounded.Computer, "Control Kiln from your PC",
        "Claude Code or Codex on your computer builds, runs and tests apps on this phone (MCP server)", trailing = {
        Toggle(s.mcpServe) { on ->
            Graph.settings.update { it.copy(mcpServe = on) }; s = Graph.settings.value
            if (on) McpServer.start(s.mcpPort) else McpServer.stop()
        }
    }) {
        if (s.mcpServe) {
            val up = McpServer.url != null && !status.startsWith("failed") && status != "stopped"
            Column(Modifier.fillMaxWidth().clip(N.shapeMd).background(N.bg.copy(alpha = 0.6f)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(if (up) "Running" else "Not running", if (up) N.ok else N.danger)
                Text(status, style = T.monoSmall)
                KButton(if (copied) "Copied" else "Copy Claude Code command", Tone.Accent) {
                    // One line that registers this phone (with its token) in Claude Code.
                    clip.setText(androidx.compose.ui.text.AnnotatedString("claude mcp add --transport http kiln ${McpServer.url} " +
                        "--header \"Authorization: Bearer ${McpServer.token}\""))
                    copied = true
                }
            }
        }
    }
}

/** Kiln as an MCP client: other servers' tools for Kiln's own agent. */
@Composable
private fun ExtraToolsCard() {
    var s by remember { mutableStateOf(Graph.settings.value) }
    var name by remember { mutableStateOf("") }; var url by remember { mutableStateOf("") }; var token by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    Section(Icons.Rounded.Hub, "Extra tools for Kiln's agent",
        "Connect MCP servers (GitHub, a database, your own service); their tools appear in new chats") {
        if (s.mcpServers.isEmpty() && !adding) Text("None connected.", style = T.label)
        s.mcpServers.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(m.name, style = T.subtitle); Text(m.url, style = T.monoSmall) }
                KButton("Remove", Tone.Danger) { Graph.settings.update { it.copy(mcpServers = it.mcpServers - m) }; s = Graph.settings.value }
            }
        }
        if (!adding) KButton("Add server") { adding = true }
        else {
            KField("Name", name, { name = it }); KField("URL", url, { url = it }, mono = true)
            KField("Bearer token (optional)", token, { token = it }, mono = true, secret = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KButton("Cancel") { adding = false }
                KButton("Add", Tone.Accent, enabled = name.isNotBlank() && url.isNotBlank()) {
                    Graph.settings.update { it.copy(mcpServers = it.mcpServers + McpServerConfig(name.trim(), url.trim(), token.trim().ifBlank { null })) }
                    s = Graph.settings.value; name = ""; url = ""; token = ""; adding = false
                }
            }
        }
    }
}

@Composable
private fun EvalsCard() {
    val state by Evals.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Section(Icons.Rounded.Science, "Evals", "A benchmark for your model setup") {
        Text("Builds six test apps from scratch with your current models (Counter, Todo, Timer, Tabs, Notes, Weather) " +
            "and scores each: does it build, does it run without crashing, plus steps, cost and time. Run it after changing " +
            "models or settings to see whether results got better. It takes a while and uses API credit (free with a local model).",
            style = T.bodySmall)
        KButton(if (state.running) "Running ${state.done}/${state.total}…" else "Run evals", Tone.Accent, enabled = !state.running) {
            // In the runs' scope: leaving Settings cancelled the benchmark half-way.
            KilnVM.runScope.launch { Evals.run() }
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
