package app.kiln.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kiln.Graph
import app.kiln.device.Device
import app.kiln.device.Warden
import app.kiln.ui.theme.N
import app.kiln.ui.theme.T
import app.kiln.ui.theme.vCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the editor has open: a source file in the project, or a file in the app's private storage. */
private sealed interface Open {
    val path: String
    data class Source(val file: File, override val path: String) : Open
    data class Data(override val path: String) : Open
}

/**
 * This project's files, two roots kept apart: **Source** (what the agent edits)
 * and **App data** (the installed app's private storage, via Warden + run-as).
 * Both are viewable and editable.
 */
@Composable
fun FilesTab(vm: KilnVM, ps: ProjectState) {
    var root by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<Open?>(null) }
    val o = open
    if (o != null) { Editor(vm, ps, o) { open = null }; return }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KChip("Source", root == 0) { root = 0 }
            KChip("App data", root == 1) { root = 1 }
        }
        if (root == 0) SourceList(ps) { open = it } else DataList(vm, ps) { open = it }
    }
}

private fun iconFor(path: String): ImageVector = when (path.substringAfterLast('.', "").lowercase()) {
    "kt", "java" -> Icons.Rounded.Code
    "json", "xml", "pb", "preferences_pb" -> Icons.Rounded.DataObject
    "png", "jpg", "webp", "svg" -> Icons.Rounded.Image
    "db", "db-wal", "db-shm" -> Icons.Rounded.Storage
    else -> Icons.Rounded.Description
}

@Composable
private fun SourceList(ps: ProjectState, onOpen: (Open) -> Unit) {
    val loop by ps.loop.collectAsStateWithLifecycle()
    // Re-list whenever the agent finishes a step.
    val feed = loop?.feed?.collectAsStateWithLifecycle()?.value
    val tick = feed?.count { it.status != app.kiln.agent.Activity.Status.RUNNING } ?: 0
    val files = remember(ps.project, tick) { ps.project.files().map { ps.project.rel(it) to it }.sortedBy { it.first } }
    // Folders as section headers; the package path is collapsed so Kotlin files don't drown in it.
    val pkgDir = "src/" + ps.pkg.replace('.', '/') + "/"
    val groups = files.groupBy { (rel, _) -> rel.removePrefix(pkgDir).let { r -> if (rel.startsWith(pkgDir)) "src" + (r.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "/$it" }) else rel.substringBeforeLast('/', "") } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
        groups.toSortedMap(compareBy({ it.isNotEmpty() }, { it })).forEach { (dir, list) ->
            item(key = "d:$dir") { FolderHeader(dir.ifEmpty { "/" }) }
            items(list, key = { it.first }) { (rel, f) ->
                FileRow(iconFor(rel), rel.substringAfterLast('/'), humanBytes(f.length()) + " · " + relativeTime(f.lastModified())) {
                    onOpen(Open.Source(f, rel))
                }
            }
        }
    }
}

@Composable
private fun FolderHeader(dir: String) {
    Row(Modifier.padding(start = 6.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Folder, null, tint = N.textMuted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(dir, style = T.overline)
    }
}

@Composable
private fun FileRow(icon: ImageVector, name: String, meta: String, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(N.shapeMd).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(N.shapeMd).background(N.surface), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = N.accent2, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = T.label.copy(color = N.textMuted))
        }
        trailing?.invoke()
    }
}

@Composable
private fun DataList(vm: KilnVM, ps: ProjectState, onOpen: (Open) -> Unit) {
    val warden by vm.warden.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    var files by remember { mutableStateOf<Result<List<Device.DataFile>>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val ready = warden == Warden.Status.READY
    val isInstalled = ps.pkg in installed
    LaunchedEffect(ready, isInstalled, tick) {
        files = if (ready && isInstalled) withContext(Dispatchers.IO) { Graph.device.dataFiles(ps.pkg) } else null
    }
    when {
        !ready -> EmptyState(Icons.Rounded.Storage, "Warden isn't connected", "App data lives in the app's private storage. Kiln reads it through Warden.")
        !isInstalled -> EmptyState(Icons.Rounded.Storage, "Not installed yet", "Run the app once; its saved data shows up here.")
        files == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(22.dp), color = N.accent, strokeWidth = 2.dp)
        }
        files!!.isFailure -> EmptyState(Icons.Rounded.Storage, "Can't read app data",
            (files!!.exceptionOrNull()?.message ?: "") + "\nRebuild with Run — older builds weren't debuggable.")
        else -> {
            val list = files!!.getOrThrow()
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("/data/data/${ps.pkg}", style = T.monoSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconBtn(Icons.Rounded.Refresh, "Refresh") { tick++ }
                }
                if (list.isEmpty()) EmptyState(Icons.Rounded.Storage, "Nothing saved yet", "The app hasn't written any files.")
                else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
                    list.groupBy { it.path.substringBeforeLast('/', "") }.forEach { (dir, fs) ->
                        item(key = "d:$dir") { FolderHeader(dir.ifEmpty { "/" }) }
                        items(fs, key = { it.path }) { f ->
                            FileRow(iconFor(f.path), f.path.substringAfterLast('/'), humanBytes(f.size), trailing = {
                                IconBtn(Icons.Rounded.Delete, "Delete", tint = N.textMuted) {
                                    scope.launch { withContext(Dispatchers.IO) { Graph.device.deleteData(ps.pkg, f.path) }; tick++ }
                                }
                            }) { onOpen(Open.Data(f.path)) }
                        }
                    }
                }
            }
        }
    }
}

private fun looksBinary(b: ByteArray) = b.take(4096).any { it == 0.toByte() }

/** Code editor: line-number gutter, no wrapping, save when dirty. */
@Composable
private fun Editor(vm: KilnVM, ps: ProjectState, o: Open, onClose: () -> Unit) {
    var original by remember(o) { mutableStateOf<String?>(null) }
    var binary by remember(o) { mutableStateOf(false) }
    var text by remember(o) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(o) {
        val bytes = withContext(Dispatchers.IO) {
            when (o) { is Open.Source -> runCatching { o.file.readBytes() }.getOrNull(); is Open.Data -> Graph.device.readData(ps.pkg, o.path) }
        } ?: ByteArray(0)
        binary = looksBinary(bytes)
        val raw = if (binary) "" else bytes.decodeToString()
        // One-line JSON (KStore, prefs exports) is unreadable on a phone: open it pretty-printed.
        text = if (o.path.endsWith(".json") && '\n' !in raw.trim())
            runCatching { app.kiln.core.KJPretty.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), app.kiln.core.parseJson(raw)) }.getOrDefault(raw)
        else raw
        original = text
    }
    val dirty = original != null && text != original
    fun save() = scope.launch {
        val ok = withContext(Dispatchers.IO) {
            when (o) {
                is Open.Source -> runCatching { o.file.writeText(text) }.isSuccess
                is Open.Data -> Graph.device.writeData(ps.pkg, o.path, text.toByteArray()).ok
            }
        }
        if (ok) original = text
        vm.message.value = if (ok) (if (o is Open.Data) "Saved — restart the app to reload it" else "Saved") else "Save failed"
    }
    Column(Modifier.fillMaxSize()) {
        KTopBar(o.path.substringAfterLast('/'), subtitle = (if (o is Open.Data) "app data · " else "") + o.path, onBack = onClose) {
            IconBtn(Icons.Rounded.Save, "Save", tint = N.accent, enabled = dirty && !binary) { save() }
        }
        when {
            original == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), color = N.accent, strokeWidth = 2.dp)
            }
            binary -> EmptyState(iconFor(o.path), "Binary file", "This file isn't text, so it can't be edited here.")
            else -> {
                val lines = text.count { it == '\n' } + 1
                val style = T.mono.copy(color = N.text)
                Row(Modifier.fillMaxSize().padding(horizontal = 8.dp).vCard(N.shapeLg).verticalScroll(rememberScrollState())) {
                    Text((1..lines).joinToString("\n"), style = style.copy(color = N.textMuted, textAlign = androidx.compose.ui.text.style.TextAlign.End),
                        modifier = Modifier.widthIn(min = 28.dp).background(N.bg.copy(alpha = 0.4f)).padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 48.dp))
                    Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                        BasicTextField(text, { text = it }, textStyle = style, cursorBrush = SolidColor(N.accent),
                            modifier = Modifier.widthIn(min = 600.dp).heightIn(min = 600.dp)
                                .padding(start = 10.dp, end = 24.dp, top = 12.dp, bottom = 48.dp))
                    }
                }
            }
        }
    }
}
