package app.kiln.ui

import androidx.compose.foundation.layout.Arrangement
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
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
