package app.kiln.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import app.kiln.device.Warden
import app.kiln.toolchain.Toolchain
import app.kiln.ui.theme.KilnTheme
import app.kiln.ui.theme.N
import app.kiln.ui.theme.T
import app.kiln.ui.theme.vCard
import kotlinx.serialization.Serializable

@Serializable data object ProjectsKey : NavKey
@Serializable data class ProjectKey(val name: String) : NavKey
@Serializable data object SettingsKey : NavKey

class MainActivity : ComponentActivity() {
    private val vm: KilnVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Kiln is always dark: light system-bar icons regardless of the system theme.
        enableEdgeToEdge(androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContent { KilnTheme { App(vm) } }
        // Only a fresh start: a recreated activity (rotation, process restore) would re-add the crash draft.
        // Nor when reopened from Recents, which re-delivers the original (crash) intent.
        if (savedInstanceState == null && intent.flags and android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) handle(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); handle(intent) }

    /** "Fix with Kiln" on a crash notification: open that project with the crash in the message box. */
    private fun handle(intent: android.content.Intent?) {
        val project = intent?.getStringExtra(EXTRA_PROJECT) ?: return
        intent.getStringExtra(EXTRA_DRAFT)?.let { vm.state(project).draft.value = it }
        vm.openRequest.value = project
        intent.removeExtra(EXTRA_PROJECT)
    }

    companion object {
        const val EXTRA_PROJECT = "app.kiln.extra.PROJECT"
        const val EXTRA_DRAFT = "app.kiln.extra.DRAFT"
    }

    override fun onStart() { super.onStart(); app.kiln.agent.Attention.visible = true }
    override fun onStop() { super.onStop(); app.kiln.agent.Attention.visible = false }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }
}

@Composable
private fun App(vm: KilnVM) {
    val backStack = rememberNavBackStack(ProjectsKey)
    val snack = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()
    // Android 13+: without this the "Kiln is working" notification (and the run's status) is hidden.
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
        val ctx = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(Unit) {
            if (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // Collected, not keyed: clearing the message changed the key and cancelled the snackbar a frame after it showed.
    LaunchedEffect(Unit) { vm.message.collect { m -> if (m != null) { vm.message.value = null; snack.showSnackbar(m) } } }
    val openRequest by vm.openRequest.collectAsStateWithLifecycle()
    LaunchedEffect(openRequest) {
        val p = openRequest ?: return@LaunchedEffect
        vm.openRequest.value = null
        if ((backStack.lastOrNull() as? ProjectKey)?.name != p) { while (backStack.size > 1) backStack.removeLastOrNull(); backStack.add(ProjectKey(p)) }
    }
    Box(Modifier.fillMaxSize().background(N.bg).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        // Never pop the last entry: a fast double back-tap would empty the stack and crash NavDisplay.
        val back = { if (backStack.size > 1) backStack.removeLastOrNull() }
        NavDisplay(backStack = backStack, onBack = { back() }, entryProvider = entryProvider {
            entry<ProjectsKey> {
                ProjectsScreen(vm, open = { backStack.add(ProjectKey(it)) }, settings = { backStack.add(SettingsKey) })
            }
            entry<ProjectKey> { key -> ProjectScreen(vm, key.name, back = { back() }) }
            entry<SettingsKey> { SettingsScreen(vm) { back() } }
        })
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 76.dp)) {
            Snackbar(it, containerColor = N.surfaceHi, contentColor = N.text, shape = N.shapeLg)
        }
    }
}

// ───────────────────────────── Projects ─────────────────────────────

@Composable
private fun ProjectsScreen(vm: KilnVM, open: (String) -> Unit, settings: () -> Unit) {
    // Coming back here (from a project, or after a run installed something): fresh install states.
    LaunchedEffect(Unit) { vm.refresh() }
    val projects by vm.projects.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val toolchain by vm.toolchain.collectAsStateWithLifecycle()
    val warden by vm.warden.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<ProjectInfo?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { KTopBar("Kiln") { IconBtn(Icons.Rounded.Settings, "Settings", onClick = settings) } }
        if (toolchain !is Toolchain.State.Ready) item { ToolchainSetupCard(toolchain) { vm.setupToolchain() } }
        if (warden != Warden.Status.READY) item {
            SetupCard(Icons.Rounded.Link, "Connect Warden", when (warden) {
                Warden.Status.NOT_GRANTED -> "Warden is running, but Kiln isn't allowed yet. Switch Kiln on in Warden's app list."
                Warden.Status.NOT_INSTALLED -> "Install Warden to let Kiln install, run and test the apps it builds."
                else -> "Start Warden so Kiln can install, run and test apps. Building works without it."
            }, "Check again") { vm.refresh() }
        }
        item { NewAppCard(enabled = toolchain is Toolchain.State.Ready) { label, prompt -> vm.createProject(label, prompt, open) } }
        if (projects.isNotEmpty()) item { Overline("Your apps", Modifier.padding(start = 16.dp, top = 12.dp)) }
        items(projects, key = { it.project.name }) { p ->
            ProjectCard(p, installed = p.pkg in installed, onOpen = { open(p.project.name) }, onDelete = { deleting = p },
                onDuplicate = { vm.duplicateProject(p.project.name, open) })
        }
    }
    deleting?.let { p ->
        AlertDialog(onDismissRequest = { deleting = null }, containerColor = N.surface,
            title = { Text("Delete ${p.label}?", style = T.cardTitle) },
            text = { Text("Removes its source, chats and signing key, and uninstalls the app. This can't be undone.", style = T.bodySmall) },
            confirmButton = { TextButton(onClick = { vm.deleteProject(p.project.name); deleting = null }) { Text("Delete", color = N.danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = N.textLabel) } })
    }
}

@Composable
private fun Overline(text: String, modifier: Modifier = Modifier) = app.kiln.ui.theme.Overline(text, modifier)

/** First launch (or an update that changed the toolchain): unpacking what came with the app. */
@Composable
private fun ToolchainSetupCard(state: Toolchain.State, retry: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().vCard(N.shapeLg).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = N.accent2, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(if (state is Toolchain.State.Failed) "Setup didn't finish" else "Setting up", style = T.subtitle)
        }
        when (state) {
            is Toolchain.State.Failed -> {
                Text(state.message, style = T.bodySmall.copy(color = N.danger))
                KButton("Try again", Tone.Accent, onClick = retry)
            }
            is Toolchain.State.Installing -> {
                Text("Unpacking the build tools that came with Kiln (JDK, Kotlin, Compose). This happens once per update and needs no network.", style = T.bodySmall)
                LinearProgressIndicator(progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f },
                    color = N.accent, trackColor = N.bg, modifier = Modifier.fillMaxWidth())
                Text("${state.step} · ${state.done / 1_048_576} of ${state.total / 1_048_576} MB", style = T.label)
            }
            else -> Text("Checking the build tools…", style = T.bodySmall)
        }
    }
}

@Composable
private fun SetupCard(icon: ImageVector, title: String, body: String, action: String, onAction: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().vCard(N.shapeLg, N.warn.copy(alpha = 0.45f)).padding(14.dp),
        verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = N.warn, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = T.subtitle)
            Text(body, style = T.bodySmall)
            KButton(action, Tone.Accent, Modifier.padding(top = 4.dp), onClick = onAction)
        }
    }
}

/** "What do you want to build?" — the prompt starts a new project and its first chat in one step. */
@Composable
private fun NewAppCard(enabled: Boolean, onCreate: (label: String, prompt: String) -> Unit) {
    var prompt by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var nameEdited by rememberSaveable { mutableStateOf(false) }
    val suggested = remember(prompt) { suggestName(prompt) }
    val label = if (nameEdited) name else suggested
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().vCard(RoundedCornerShape(20.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("What do you want to build?", style = T.h4)
        Box(Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
            if (prompt.isEmpty()) Text("A habit tracker with a weekly chart and a daily reminder…", style = T.body.copy(color = N.textMuted))
            BasicTextField(prompt, { prompt = it }, textStyle = T.body, cursorBrush = SolidColor(N.accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 200.dp).fieldLabel("App description"))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(N.bg).border(1.dp, N.divider, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                if (label.isNotBlank()) { AppTile(label, KilnVM.slug(label), 22.dp); Spacer(Modifier.width(8.dp)) }
                Box(Modifier.weight(1f)) {
                    if (label.isEmpty()) Text("App name", style = T.body.copy(color = N.textMuted))
                    BasicTextField(label, { name = it; nameEdited = true }, singleLine = true, textStyle = T.body,
                        cursorBrush = SolidColor(N.accent), modifier = Modifier.fillMaxWidth().fieldLabel("App name"))
                }
            }
            Spacer(Modifier.width(10.dp))
            FilledIconBtn(Icons.Rounded.ArrowUpward, "Create", enabled = enabled && label.isNotBlank() && prompt.isNotBlank()) {
                onCreate(label.trim(), prompt.trim()); prompt = ""; name = ""; nameEdited = false
            }
        }
        if (prompt.isEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TEMPLATES.forEach { (title, text) -> KChip(title, false) { prompt = text; name = title; nameEdited = true } }
        }
    }
}

/** Starting points: a name and a prompt detailed enough to build something good in one go. */
private val TEMPLATES = listOf(
    "Habit Tracker" to "A habit tracker: add habits, tick them off each day, see a 7-day streak chart per habit, and get a daily reminder notification at a time I choose. Keep everything across restarts.",
    "Tip Calculator" to "A tip calculator: bill amount, tip % slider with 10/15/20 presets, split between N people, and a big clear per-person total. Remember the last tip %.",
    "Pomodoro Timer" to "A pomodoro timer: 25-minute focus and 5-minute break cycles, start/pause/reset, a ring showing progress, a notification when each phase ends, and today's completed count.",
    "Expense Log" to "An expense log: add expenses with amount, category and note, see this month's total and a bar chart by category, swipe to delete. Keep everything across restarts.",
    "Notes" to "A notes app: a list of notes with title and preview, tap to edit, search, pin notes to the top, and share a note. Keep everything across restarts.",
    "Unit Converter" to "A unit converter for length, weight, temperature and volume: pick the category, type a value, and see it converted to every unit in that category live.",
    "Flashcards" to "A flashcards app: make decks of cards (front/back), study a deck by flipping cards, mark known/unknown, and show progress per deck. Keep everything across restarts.",
    "Water Reminder" to "A water intake tracker: tap to add a glass, a daily goal ring, a 7-day history chart, and reminders every 2 hours during the day.",
)

/** "A habit tracker with a weekly chart…" → "Habit Tracker". */
private fun suggestName(prompt: String): String {
    val stop = setOf("a", "an", "the", "app", "application", "simple", "small", "basic", "make", "build", "create", "me", "i", "want",
        "for", "to", "that", "which", "with", "my", "please", "android", "of", "and", "where", "can", "you")
    // "A shopping list: add items…" → the subject is before the first clause break.
    return prompt.split(Regex("[:;,.!?(—–-]")).first().split(Regex("[^A-Za-z0-9]+")).asSequence().filter { it.isNotBlank() }
        .dropWhile { it.lowercase() in stop }.takeWhile { it.lowercase() !in setOf("with", "that", "which", "where", "for", "and", "to") }
        .take(3).joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
}

@Composable
private fun ProjectCard(p: ProjectInfo, installed: Boolean, onOpen: () -> Unit, onDelete: () -> Unit, onDuplicate: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().vCard(N.shapeLg).clickable(onClick = onOpen)
        .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        ProjectIcon(p.project, p.label, 46.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.label, style = T.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(if (installed) N.ok else N.neutral700, Modifier.size(6.dp))
                Spacer(Modifier.width(6.dp))
                Text((if (installed) "Installed" else "Not installed") + " · edited " + relativeTime(p.edited), style = T.label)
            }
        }
        Box {
            IconBtn(Icons.Rounded.MoreVert, "More") { menu = true }
            DropdownMenu(menu, { menu = false }, containerColor = N.surfaceHi) {
                DropdownMenuItem(text = { Text("Duplicate", style = T.body) },
                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, tint = N.textLabel) }, onClick = { menu = false; onDuplicate() })
                DropdownMenuItem(text = { Text("Delete", style = T.body.copy(color = N.danger)) },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = N.danger) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

// ───────────────────────────── Project ─────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectScreen(vm: KilnVM, name: String, back: () -> Unit) {
    val ps = remember(name) { vm.state(name) }
    val loop by ps.loop.collectAsStateWithLifecycle()
    val running = loop?.running?.collectAsStateWithLifecycle()?.value ?: false
    val runStep by ps.runStep.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf(false) }
    var secrets by remember { mutableStateOf(false) }
    var schedule by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val isInstalled = ps.pkg in installed
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Column(Modifier.fillMaxSize()) {
        val stopping by ps.stopping.collectAsStateWithLifecycle()
        KTopBar(ps.label, subtitle = runStep ?: if (running) (if (stopping) "Stopping…" else "Working…") else ps.pkg, onBack = back,
            leading = { ProjectIcon(ps.project, ps.label, 34.dp) }) {
            if (runStep != null) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), color = N.accent, strokeWidth = 2.dp)
            } else IconBtn(Icons.Rounded.PlayArrow, "Run", tint = N.accent, enabled = !running) { vm.run(name) }
            if (isInstalled) IconBtn(Icons.Rounded.Smartphone, "Preview") { preview = true }
            IconBtn(Icons.Rounded.History, "Chats") { history = true }
            Box {
                IconBtn(Icons.Rounded.MoreVert, "More") { menu = true }
                DropdownMenu(menu, { menu = false }, containerColor = N.surfaceHi) {
                    DropdownMenuItem(text = { Text("New chat", style = T.body) }, leadingIcon = { Icon(Icons.Rounded.Add, null, tint = N.textLabel) },
                        onClick = { menu = false; vm.newChat(name); tab = 0 })
                    DropdownMenuItem(text = { Text("Release…", style = T.body) },
                        leadingIcon = { Icon(Icons.Rounded.RocketLaunch, null, tint = N.textLabel) }, enabled = !running,
                        onClick = { menu = false; release = true })
                    DropdownMenuItem(text = { Text("Schedule…", style = T.body) },
                        leadingIcon = { Icon(Icons.Rounded.Schedule, null, tint = N.textLabel) },
                        onClick = { menu = false; schedule = true })
                    DropdownMenuItem(text = { Text("Secrets", style = T.body) },
                        leadingIcon = { Icon(Icons.Rounded.Key, null, tint = N.textLabel) },
                        onClick = { menu = false; secrets = true })
                    DropdownMenuItem(text = { Text("Share chat", style = T.body) },
                        leadingIcon = { Icon(Icons.Rounded.Share, null, tint = N.textLabel) }, enabled = loop != null,
                        onClick = {
                            menu = false
                            vm.transcriptMarkdown(name)?.let { md ->
                                ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                                    .setType("text/markdown").putExtra(android.content.Intent.EXTRA_SUBJECT, ps.label + " chat")
                                    .putExtra(android.content.Intent.EXTRA_TEXT, md.take(200_000)), "Share chat"))
                            }
                        })
                    DropdownMenuItem(text = { Text("Redesign icon", style = T.body) },
                        leadingIcon = { Icon(Icons.Rounded.Palette, null, tint = N.textLabel) },
                        enabled = !running,
                        onClick = {
                            menu = false; tab = 0
                            vm.send(name, "Design a new launcher icon for this app in res/drawable/ic_launcher.xml, following the " +
                                "\"App icon\" rules in the kit docs: one bold glyph for what the app does, large in the safe zone, " +
                                "Nocturne colours. Then build and install it. Change nothing else.")
                        })
                    if (isInstalled) {
                        DropdownMenuItem(text = { Text("Clear app data", style = T.body) },
                            leadingIcon = { Icon(Icons.Rounded.CleaningServices, null, tint = N.textLabel) }, onClick = { menu = false; vm.clearData(name) })
                        DropdownMenuItem(text = { Text("Uninstall app", style = T.body) },
                            leadingIcon = { Icon(Icons.Rounded.RemoveCircleOutline, null, tint = N.textLabel) }, onClick = { menu = false; vm.uninstall(name) })
                    }
                    DropdownMenuItem(text = { Text("Delete project", style = T.body.copy(color = N.danger)) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = N.danger) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
        // This project's chat is on screen: its prompts show here, so its notifications are skipped (others' aren't).
        androidx.compose.runtime.DisposableEffect(name, tab) {
            app.kiln.agent.Attention.onScreen = if (tab == 0) name else null
            onDispose { if (app.kiln.agent.Attention.onScreen == name) app.kiln.agent.Attention.onScreen = null }
        }
        val tabStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        PillTabs(listOf(Icons.Rounded.ChatBubbleOutline to "Chat", Icons.Rounded.Folder to "Files", Icons.AutoMirrored.Rounded.ReceiptLong to "Logs"),
            tab, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { tab = it }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Each tab keeps its saveable state (the composer's draft, list scroll) while another is shown.
            tabStates.SaveableStateProvider(tab) {
                when (tab) {
                    0 -> ChatTab(vm, ps)
                    1 -> FilesTab(vm, ps)
                    else -> LogsTab(vm, ps) { fix -> tab = 0; vm.send(name, fix) }
                }
            }
        }
    }

    if (schedule) ScheduleSheet(vm, ps) { schedule = false }
    if (secrets) SecretsSheet(vm, ps) { secrets = false }
    if (release) ReleaseSheet(vm, ps) { release = false }
    if (preview) PreviewSheet(vm, ps, onDismiss = { preview = false }) { picked -> ps.draft.value = picked; preview = false; tab = 0 }
    if (history) ModalBottomSheet({ history = false }, containerColor = N.surface) {
        val sessions by ps.sessions.collectAsStateWithLifecycle()
        Column(Modifier.padding(horizontal = 4.dp).padding(bottom = 24.dp)) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Chats", style = T.cardTitle, modifier = Modifier.weight(1f))
                KButton("New chat", Tone.Accent) { history = false; vm.newChat(name); tab = 0 }
            }
            var query by remember { mutableStateOf("") }
            var hits by remember { mutableStateOf<Set<String>?>(null) }
            LaunchedEffect(query) {
                kotlinx.coroutines.delay(250)
                hits = if (query.isBlank()) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.searchChats(name, query.trim()) }
            }
            if (sessions.size > 1) KField("", query, { query = it }, Modifier.padding(horizontal = 12.dp).padding(top = 8.dp), hint = "Search chats")
            Spacer(Modifier.height(8.dp))
            if (hits?.isEmpty() == true) Text("No chat mentions that.", style = T.bodySmall.copy(color = N.textMuted), modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp))
            if (sessions.isEmpty()) Text("No chats yet.", style = T.bodySmall.copy(color = N.textMuted), modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp))
            // An empty chat (no message yet, so no title) is only worth listing while it is the open one.
            sessions.filter { (it.title.isNotBlank() || it.id == loop?.session?.meta?.id) && hits?.contains(it.id) != false }.take(30).forEach { s ->
                val current = loop?.session?.meta?.id == s.id
                Row(Modifier.fillMaxWidth().clip(N.shapeMd).clickable { history = false; vm.openSession(name, s.id); tab = 0 }
                    .background(if (current) N.accent800.copy(alpha = 0.5f) else androidx.compose.ui.graphics.Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ChatBubbleOutline, null, tint = if (current) N.accent2 else N.textMuted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title.ifBlank { "Untitled chat" }, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(relativeTime(s.created) + (if (s.costUsd > 0) " · $" + "%.2f".format(s.costUsd) else ""), style = T.label)
                    }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, containerColor = N.surface,
        title = { Text("Delete ${ps.label}?", style = T.cardTitle) },
        text = { Text("Removes its source, chats and signing key, and uninstalls the app. This can't be undone.", style = T.bodySmall) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteProject(name); back() }) { Text("Delete", color = N.danger) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = N.textLabel) } })
}
