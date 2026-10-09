package app.kiln.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.kiln.build.BuildEngine
import app.kiln.ui.theme.N
import app.kiln.ui.theme.T
import app.kiln.ui.theme.vCard
import kotlinx.coroutines.launch
import java.io.File

/** Build what you ship: a Play bundle or an APK, signed with the app's key, ready to share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseSheet(vm: KilnVM, ps: ProjectState, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val meta = remember { runCatching { ps.project.meta() }.getOrNull() }
    var versionName by remember { mutableStateOf(meta?.versionName ?: "1.0") }
    var versionCode by remember { mutableStateOf((meta?.versionCode ?: 1).toString()) }
    var step by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var built by remember { mutableStateOf<File?>(null) }

    fun build(kind: BuildEngine.Kind) {
        val code = versionCode.trim().toIntOrNull()
        if (code == null || code < 1) { error = "Version code must be a whole number, 1 or more"; return }
        if (versionName.isBlank()) { error = "Give the version a name, like 1.0"; return }
        error = null; built = null; step = "Starting…"
        scope.launch {
            vm.buildRelease(ps.project.name, kind, versionName.trim(), code) { step = "$it…" }
                .onSuccess { built = it }.onFailure { error = it.message }
            step = null
        }
    }

    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = N.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Release ${ps.label}", style = T.cardTitle)
            Text("A release build isn't debuggable and is signed with this app's own key.", style = T.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KField("Version name", versionName, { versionName = it }, Modifier.weight(1f), hint = "1.0")
                KField("Version code", versionCode, { versionCode = it.filter(Char::isDigit) }, Modifier.weight(1f), mono = true, hint = "1")
            }
            Text("Each upload to Play needs a higher version code than the last.", style = T.label)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KButton("Play bundle (.aab)", Tone.Accent) { if (step == null) build(BuildEngine.Kind.RELEASE_AAB) }
                KButton("APK") { if (step == null) build(BuildEngine.Kind.RELEASE_APK) }
            }
            step?.let { Text(it, style = T.label.copy(color = N.accent2)) }
            error?.let { Text(it, style = T.bodySmall.copy(color = N.danger)) }
            built?.let { f ->
                Column(Modifier.fillMaxWidth().vCard(N.shapeLg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(f.name, style = T.subtitle)
                    Text("%.1f MB".format(f.length() / 1_048_576.0) +
                        if (f.extension == "aab") " · upload this in Play Console → Test and release" else " · install it on any phone", style = T.label)
                    KButton("Share", Tone.Accent) {
                        shareFile(ctx, f, if (f.extension == "aab") "application/octet-stream" else "application/vnd.android.package-archive", "Share ${f.name}")
                    }
                }
            }
            StoreSection(vm, ps, onDismiss)
            PlaySection(vm, ps, versionName, versionCode)
            GitHubSection(vm, ps)
            Column(Modifier.fillMaxWidth().vCard(N.shapeLg, N.warn.copy(alpha = 0.4f)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Signing key", style = T.subtitle)
                Text("Every update must be signed with the same key. Keep a copy somewhere safe and private — " +
                    "the backup includes its password.", style = T.bodySmall)
                KButton("Back up key") {
                    scope.launch {
                        vm.keyBackup(ps.project.name).onSuccess { shareFile(ctx, it, "application/zip", "Back up signing key") }
                            .onFailure { error = it.message }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaySection(vm: KilnVM, ps: ProjectState, versionName: String, versionCode: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf("") }
    var step by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { email = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.playAccount() } }
    val keyScope = androidx.compose.runtime.rememberCoroutineScope()
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // At most 64 KB (a key file is ~2 KB; any file can be picked), read off the main thread.
        keyScope.launch {
            val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)!!.use { s ->
                    val buf = ByteArray(65_536); var n = 0
                    while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                    String(buf, 0, n)
                } }.getOrNull()
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.savePlayKey(json ?: "") }.onSuccess { email = it; error = null }.onFailure { error = it.message }
        }
    }
    Column(Modifier.fillMaxWidth().vCard(N.shapeLg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Google Play · internal testing", style = T.subtitle)
        val e = email
        if (e == null) {
            Text("Send a bundle straight to your testers. You need a service-account key (JSON) that's invited in Play Console " +
                "with release rights, and the app must already exist there (upload its first bundle by hand).", style = T.bodySmall)
            KButton("Add key file") { pick.launch(arrayOf("application/json", "*/*")) }
        } else {
            Text("Signed in as $e", style = T.label)
            KField("What's new (optional)", notes, { notes = it }, hint = "Release notes for testers", singleLine = false)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KButton("Upload", Tone.Accent) {
                    val code = versionCode.toIntOrNull() ?: run { error = "Set a version code"; return@KButton }
                    if (step != null) return@KButton
                    error = null; result = null; step = "Starting…"
                    scope.launch {
                        vm.uploadToPlay(ps.project.name, versionName.trim(), code, notes) { step = it }
                            .onSuccess { result = it }.onFailure { error = it.message }
                        step = null
                    }
                }
                KButton("Remove key") { vm.savePlayKey(null); email = null }
            }
        }
        step?.let { Text(it, style = T.label.copy(color = N.accent2)) }
        result?.let { Text(it, style = T.bodySmall.copy(color = N.ok)) }
        error?.let { Text(it, style = T.bodySmall.copy(color = N.danger)) }
    }
}

@Composable
private fun GitHubSection(vm: KilnVM, ps: ProjectState) {
    val scope = rememberCoroutineScope()
    val name = ps.project.name
    var login by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf(vm.githubRepo(name)) }
    var message by remember { mutableStateOf("") }
    var step by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { login = vm.githubLogin(); checked = true }
    fun busy(label: String, work: suspend () -> Result<String>) {
        if (step != null) return
        error = null; result = null; step = label
        scope.launch { work().onSuccess { result = it }.onFailure { error = it.message }; step = null }
    }
    Column(Modifier.fillMaxWidth().vCard(N.shapeLg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("GitHub", style = T.subtitle)
        val l = login
        if (!checked) Text("Checking…", style = T.label)
        else if (l == null) {
            Text("Keep the source in a repository. Use a fine-grained token with Contents read & write " +
                "(and Administration to let Kiln create the repository).", style = T.bodySmall)
            KField("", token, { token = it }, hint = "Personal access token", mono = true, secret = true)
            KButton("Save token", Tone.Accent) {
                scope.launch { vm.saveGithubToken(token).onSuccess { login = it; token = ""; error = null }.onFailure { error = it.message } }
            }
        } else {
            Text("Signed in as @$l", style = T.label)
            // Saved once typing pauses, off the main thread (it wrote a file per keystroke).
            androidx.compose.runtime.LaunchedEffect(repo) { kotlinx.coroutines.delay(600); kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.setGithubRepo(name, repo) } }
            KField("Repository", repo, { repo = it.trim() }, hint = "$l/${name.replace('_', '-')}", mono = true)
            KField("", message, { message = it }, hint = "What changed (commit message)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KButton("Push", Tone.Accent) { busy("Pushing…") { vm.pushToGithub(name, message) { step = it } } }
                if (repo.isBlank()) KButton("Create private repo") {
                    busy("Creating…") { vm.createGithubRepo(name).map { repo = it; "Created $it" } }
                }
                KButton("Sign out") { scope.launch { vm.saveGithubToken(null); login = null } }
            }
        }
        step?.let { Text(it, style = T.label.copy(color = N.accent2)) }
        result?.let { Text(it, style = T.bodySmall.copy(color = N.ok)) }
        error?.let { Text(it, style = T.bodySmall.copy(color = N.danger)) }
    }
}

@Composable
private fun StoreSection(vm: KilnVM, ps: ProjectState, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf(vm.storeFiles(ps.project.name)) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().vCard(N.shapeLg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Store listing", style = T.subtitle)
        Text(if (files.isEmpty()) "Kiln writes the listing text, takes the screenshots and reviews Play policy, all into store/."
            else files.joinToString(" · ") { it.removePrefix("store/") }, style = T.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KButton(if ("store/listing.md" in files) "Redo listing" else "Prepare listing", Tone.Accent) {
                vm.send(ps.project.name, STORE_LISTING_PROMPT); onDismiss()
            }
            KButton("Feature graphic") {
                scope.launch { vm.featureGraphic(ps.project.name).onSuccess { files = vm.storeFiles(ps.project.name); error = null }.onFailure { error = it.message } }
            }
        }
        error?.let { Text(it, style = T.bodySmall.copy(color = N.danger)) }
    }
}
