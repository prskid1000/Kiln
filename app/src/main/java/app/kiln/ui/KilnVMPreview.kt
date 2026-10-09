package app.kiln.ui

import android.graphics.Bitmap
import app.kiln.Graph
import app.kiln.device.UiNode
import java.io.File

// The live preview: the hidden test display, shown in Kiln and operated by touch.

/** A picked element and where its text comes from in the source. */
data class Picked(val node: UiNode, val sources: List<SourceHit>, val label: String)
data class SourceHit(val path: String, val line: Int, val code: String)

val KilnVM.previewDevice get() = Graph.testDevice

suspend fun KilnVM.previewFrame(): Bitmap? = Graph.testDevice.testDisplay?.bitmap(wait = false)

suspend fun KilnVM.previewSize(): Pair<Int, Int> =
    Graph.testDevice.testDisplay?.let { it.id(); it.width to it.height } ?: (0 to 0)

/** Start (or restart) the app on the hidden display; null when it went fine. */
suspend fun KilnVM.previewLaunch(name: String): String? {
    val s = state(name)
    if (!Graph.device.isInstalled(s.pkg)) return "Run the app once first — it isn't installed yet"
    val r = Graph.testDevice.launch(s.pkg)
    return if (r.ok) null else r.all.take(200)
}

suspend fun KilnVM.previewIsShowing(name: String): Boolean = Graph.testDevice.foregroundPackage() == state(name).pkg

/** The smallest element on screen that contains the point, with source lines that mention its text. */
suspend fun KilnVM.previewPick(name: String, x: Int, y: Int): Picked? {
    val nodes = Graph.testDevice.uiTree(expect = state(name).pkg)
    val hit = nodes.filter { x in it.left..it.right && y in it.top..it.bottom && it.right > it.left }
        .sortedWith(compareBy<UiNode>({ (it.right - it.left).toLong() * (it.bottom - it.top) }, { it.text.isBlank() && it.desc.isBlank() }))
    // Prefer the smallest element that says something; fall back to the smallest one.
    val node = hit.firstOrNull { it.text.isNotBlank() || it.desc.isNotBlank() } ?: hit.firstOrNull() ?: return null
    // A container (a list row, a card) says what it holds: the texts inside it.
    val inside = nodes.filter { it !== node && it.left >= node.left && it.right <= node.right && it.top >= node.top && it.bottom <= node.bottom }
        .flatMap { listOf(it.text, it.desc) }.filter { it.isNotBlank() }.distinct()
    val own = listOf(node.text, node.desc).filter { it.isNotBlank() }
    val label = own.firstOrNull() ?: inside.take(4).joinToString(" · ").ifBlank { node.cls.substringAfterLast('.') }
    // Reads every source file: off the main thread (the pick runs in a UI scope).
    return Picked(node, kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { findSource(name, own.ifEmpty { inside.take(4) }) }, label)
}

private fun KilnVM.findSource(name: String, texts: List<String>): List<SourceHit> {
    if (texts.isEmpty()) return emptyList()
    val p = state(name).project
    val out = mutableListOf<SourceHit>()
    for (f in p.files().filter { it.extension == "kt" || it.extension == "xml" }) {
        f.readLines().forEachIndexed { i, line ->
            if (texts.any { t -> "\"$t\"" in line || ">$t<" in line }) out += SourceHit(p.rel(f), i + 1, line.trim().take(160))
        }
    }
    return out.take(8)
}

/**
 * Change a piece of UI text directly in the source — no model, no credits — then rebuild,
 * reinstall and reopen the app on the hidden display. Returns an error, or null.
 */
suspend fun KilnVM.quickEditText(name: String, hit: SourceHit, old: String, new: String): String? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { quickEdit(name, hit, old, new) }   // file IO and the build: never on Main

private suspend fun KilnVM.quickEdit(name: String, hit: SourceHit, old: String, new: String): String? {
    val s = state(name)
    if (s.loop.value?.running?.value == true) return "Kiln is working on this app — wait for it to finish"
    val f = runCatching { s.project.resolveWritable(hit.path) }.getOrNull() ?: return "File not found"
    val lines = f.readLines().toMutableList()
    val i = hit.line - 1
    if (i !in lines.indices) return "The file changed — pick the element again"
    // A resource string is escaped for Android XML ("Don't", "Tom & Jerry" broke aapt2).
    val (from, to) = if ("\"$old\"" in lines[i]) "\"$old\"" to "\"${escapeKotlin(new)}\"" else ">$old<" to ">${escapeAndroidXml(new)}<"
    if (from !in lines[i]) return "The file changed — pick the element again"
    val before = f.readText()
    lines[i] = lines[i].replaceFirst(from, to)
    f.writeText(lines.joinToString(System.lineSeparator(), postfix = System.lineSeparator()))
    val r = Graph.builds.build(s.project)
    if (!r.ok) {
        // Put the file back: a failed quick edit must not leave the next build broken too.
        runCatching { f.writeText(before) }
        return "Build failed (the edit was undone): " + r.diagnostics.firstOrNull { it.severity == "error" }?.let { "${it.file?.let(::File)?.name}:${it.line} ${it.message}" }.orEmpty()
    }
    val inst = Graph.testDevice.install(File(r.apk!!))
    if (!inst.ok) return "Install failed: " + inst.all.take(200)
    Graph.testDevice.launch(s.pkg)
    IconCache.version.value++
    return null
}

/** Text for an Android string resource: XML entities, escaped quotes, and a leading @ or ? that isn't a reference. */
private fun escapeAndroidXml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
    .let { if (it.startsWith("@") || it.startsWith("?")) "\\$it" else it }

private fun escapeKotlin(s: String) = buildString {
    for (c in s) when (c) {
        '"' -> append(BS).append('"')
        '$' -> append(BS).append('$')
        BS -> append(BS).append(BS)
        else -> append(c)
    }
}

private val BS = 92.toChar()
