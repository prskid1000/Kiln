package app.kiln.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.kiln.Graph
import app.kiln.build.Project
import app.kiln.device.Warden
import app.kiln.ui.theme.N
import app.kiln.ui.theme.T
import app.kiln.ui.theme.vCard
import app.kiln.ui.theme.vInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Project files, read-only (the agent edits; you read). */
@Composable
fun FilesTab(project: Project?) {
    if (project == null) { Text("No project", style = T.body, modifier = Modifier.padding(16.dp)); return }
    var open by remember(project) { mutableStateOf<File?>(null) }
    var tick by remember { mutableStateOf(0) }
    val files = remember(project, tick) { project.files() }
    val f = open
    if (f != null) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(project.rel(f), style = T.mono.copy(color = N.text), modifier = Modifier.weight(1f))
                KButton("Close") { open = null; tick++ }
            }
            val text = remember(f) { runCatching { f.readText() }.getOrDefault("(binary)") }
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).vInset().verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()).padding(10.dp)) {
                text.lines().forEachIndexed { i, l -> Text("%4d  %s".format(i + 1, l), style = T.monoSmall.copy(color = N.textLabel), softWrap = false) }
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(files, key = { it.path }) { file ->
            Row(Modifier.fillMaxWidth().clip(N.shapeMd).clickable { open = file }.padding(10.dp)) {
                Text(project.rel(file), style = T.mono.copy(color = N.text), modifier = Modifier.weight(1f))
                Text("${file.length()} B", style = T.monoSmall)
            }
        }
    }
}

/** Warden status, the app on screen, its log tail, and every Kiln-built app. */
@Composable
fun DeviceTab(vm: KilnVM, project: Project?, wardenStatus: Warden.Status) {
    val scope = rememberCoroutineScope()
    var shot by remember { mutableStateOf<ByteArray?>(null) }
    var logs by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<String>>(emptyList()) }
    val ready = wardenStatus == Warden.Status.READY
    LaunchedEffect(ready) { if (ready) apps = withContext(Dispatchers.IO) { Graph.device.installedApps() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().vCard().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(if (ready) N.ok else N.warn); Spacer(Modifier.width(8.dp))
                Text("Warden: " + when (wardenStatus) {
                    Warden.Status.READY -> "ready"; Warden.Status.NOT_GRANTED -> "running, but Kiln is not granted"
                    Warden.Status.NOT_RUNNING -> "not running"; Warden.Status.NOT_INSTALLED -> "not installed" }, style = T.subtitle)
            }
            Text(if (ready) "Kiln can install, launch, screenshot and test apps on this phone."
                 else "Building works without Warden. Installing and testing apps needs it: start Warden and switch Kiln on in its Apps list.",
                style = T.bodySmall)
            KButton("Refresh") { vm.refreshWarden() }
        }
        if (ready && project != null) {
            val pkg = runCatching { project.meta().`package` }.getOrNull()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KButton("Screenshot", Tone.Accent) { scope.launch { shot = withContext(Dispatchers.IO) { Graph.device.screenshot() } } }
                if (pkg != null) {
                    KButton("Launch") { scope.launch(Dispatchers.IO) { Graph.device.launch(pkg) } }
                    KButton("Logs") { scope.launch { logs = withContext(Dispatchers.IO) { Graph.device.logcat(pkg, null, "I", 200) } } }
                }
            }
            shot?.let { png ->
                val bmp = remember(png) { BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap() }
                if (bmp != null) Image(bmp, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).clip(N.shapeMd))
            }
            if (logs.isNotBlank()) Text(logs.lines().takeLast(120).joinToString("\n"), style = T.monoSmall,
                modifier = Modifier.fillMaxWidth().vInset().horizontalScroll(rememberScrollState()).padding(10.dp), softWrap = false)
        }
        if (apps.isNotEmpty()) {
            Text("APPS BUILT WITH KILN", style = T.overline)
            apps.forEach { p ->
                Row(Modifier.fillMaxWidth().vCard().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p, style = T.mono.copy(color = N.text), modifier = Modifier.weight(1f))
                    KButton("Open") { scope.launch(Dispatchers.IO) { Graph.device.launch(p) } }
                    Spacer(Modifier.width(6.dp))
                    KButton("Remove", Tone.Danger) { scope.launch { withContext(Dispatchers.IO) { Graph.device.uninstall(p) }; apps = apps - p } }
                }
            }
        }
    }
}
