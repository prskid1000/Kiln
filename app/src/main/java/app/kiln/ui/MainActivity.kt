package app.kiln.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kiln.build.Project
import app.kiln.toolchain.Toolchain
import app.kiln.ui.theme.KilnTheme
import app.kiln.ui.theme.N
import app.kiln.ui.theme.T
import app.kiln.ui.theme.vCard

class MainActivity : ComponentActivity() {
    private val vm: KilnVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { KilnTheme { Home(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshWarden(); vm.autoImportPack()
    }
}

@Composable
private fun Home(vm: KilnVM) {
    val toolchain by vm.toolchain.collectAsStateWithLifecycle()
    val project by vm.project.collectAsStateWithLifecycle()
    val loop by vm.loop.collectAsStateWithLifecycle()
    val warden by vm.warden.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var settings by remember { mutableStateOf(false) }
    var newProject by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    LaunchedEffect(message) { message?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show(); vm.message.value = null } }

    Box(Modifier.fillMaxSize().background(N.bg).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        if (settings) { SettingsScreen(vm) { settings = false }; return@Box }
        Column(Modifier.fillMaxSize()) {
            TopBar(vm, project, onNew = { newProject = true }, onSettings = { settings = true })
            if (toolchain !is Toolchain.State.Ready) {
                Column(Modifier.padding(16.dp).fillMaxWidth().vCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("One-time setup", style = T.subtitle)
                    Text("Kiln builds apps with an on-device toolchain (JDK, Kotlin, Compose, the app kit). Import the toolchain pack to start.", style = T.bodySmall)
                    if (toolchain is Toolchain.State.Installing) Text("Installing…", style = T.label.copy(color = N.accent))
                    KButton("Open settings", Tone.Accent) { settings = true }
                }
            } else if (project == null) {
                Column(Modifier.padding(16.dp).fillMaxWidth().vCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Start an app", style = T.subtitle)
                    Text("Each app is a project: Kotlin + Compose with the Kiln kit, signed with its own key.", style = T.bodySmall)
                    KButton("New project", Tone.Accent) { newProject = true }
                }
            } else {
                SegTabs(listOf("Chat" to "", "Files" to "", "Device" to ""), tab, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { tab = it }
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        0 -> ChatTab(vm, loop)
                        1 -> FilesTab(project)
                        else -> DeviceTab(vm, project, warden)
                    }
                }
            }
        }
        if (newProject) NewProjectDialog(onDismiss = { newProject = false }) { name, label -> vm.createProject(name, label); newProject = false; tab = 0 }
    }
}

@Composable
private fun TopBar(vm: KilnVM, project: Project?, onNew: () -> Unit, onSettings: () -> Unit) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    var pickProject by remember { mutableStateOf(false) }
    var pickSession by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Kiln", style = T.h3)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            Text(project?.let { runCatching { it.meta().label }.getOrDefault(it.name) + " ▾" } ?: "", style = T.subtitle.copy(color = N.accent2),
                modifier = Modifier.clickable { pickProject = true })
            DropdownMenu(pickProject, { pickProject = false }, containerColor = N.surface) {
                projects.forEach { p -> DropdownMenuItem(text = { Text(p.name, style = T.body) }, onClick = { vm.open(p); pickProject = false }) }
                DropdownMenuItem(text = { Text("+ New project", style = T.body.copy(color = N.accent)) }, onClick = { pickProject = false; onNew() })
            }
        }
        if (project != null) Box {
            KButton("Chats") { pickSession = true }
            DropdownMenu(pickSession, { pickSession = false }, containerColor = N.surface) {
                DropdownMenuItem(text = { Text("+ New chat", style = T.body.copy(color = N.accent)) }, onClick = { vm.newChat(); pickSession = false })
                sessions.take(20).forEach { s -> DropdownMenuItem(text = { Text(s.title.ifBlank { s.id }, style = T.body) },
                    onClick = { vm.openSession(s.id); pickSession = false }) }
            }
        }
        Spacer(Modifier.width(8.dp))
        KButton("⚙", onClick = onSettings)
    }
}

@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var label by remember { mutableStateOf("") }
    val name = label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').let { if (it.firstOrNull()?.isLetter() == true) it else "app_$it" }.take(40)
    AlertDialog(onDismissRequest = onDismiss, containerColor = N.surface,
        title = { Text("New project", style = T.cardTitle) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KField("App name", label, { label = it }, hint = "e.g. Water Tracker")
            if (label.isNotBlank()) Text("package kiln.app.$name", style = T.monoSmall)
        } },
        confirmButton = { TextButton(onClick = { onCreate(name, label.trim()) }, enabled = Project.NAME.matches(name) && label.isNotBlank()) { Text("Create", color = N.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = N.textLabel) } })
}
