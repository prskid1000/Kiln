package app.kiln.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import app.kiln.core.int
import app.kiln.core.str
import com.caverock.androidsvg.SVG
import kotlinx.serialization.json.JsonObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory

private fun JsonObject.req(k: String) = str(k) ?: throw IllegalArgumentException("missing '$k'")

/**
 * Turns SVG the model writes into app assets: a vector drawable (res/drawable/x.xml), a PNG of
 * any size, or the SVG itself (assets/x.svg). Always renders a preview so the model sees it.
 */
class MakeGraphicTool : Tool {
    override val name = "make_graphic"
    override val description = "Create a graphic asset from SVG you write: icons, illustrations, logos, empty-state art, " +
        "backgrounds, the launcher icon. path decides the format — res/drawable/<name>.xml (vector drawable: crisp at any " +
        "size, use with painterResource(R.drawable.<name>)), res/drawable-nodpi/<name>.png or assets/<name>.png (PNG at `size` px), " +
        "or assets/<name>.svg (show with KSvg). Returns a rendered preview so you can check it; iterate until it looks right. " +
        "Supports path, rect, circle, ellipse, line, polyline, polygon, g with transforms, fill/stroke/opacity and linear/radial gradients."
    override val schema = schema {
        str("svg", "The SVG document. Give it a viewBox (e.g. 0 0 108 108 for a launcher icon, 0 0 24 24 for an icon).")
        str("path", "Output file relative to the project, e.g. res/drawable/ic_cart.xml, res/drawable-nodpi/hero.png, assets/logo.svg.")
        int("size", "PNG width in px (height follows the viewBox). Default 512.", required = false)
    }
    override val traits = emptySet<Trait>()

    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        // Models also write Android vector XML here, often with xmlns="android=…" (run 12): repair, accept, preview.
        val svgText = repairNamespaces(input.req("svg"))
        val rel = input.req("path")
        val size = (input.int("size") ?: 512).coerceIn(16, 4096)
        xmlProblem(svgText)?.let { return ToolResult.error("the XML doesn't parse — $it") }
        val f = ctx.project.resolveWritable(rel)
        if (isVectorDrawable(svgText)) {
            if (f.extension.lowercase() != "xml" || !rel.replace('\\', '/').startsWith("res/drawable"))
                return ToolResult.error("that's an Android vector drawable; it can only be saved as res/drawable/<name>.xml. For a PNG or an .svg file, send SVG.")
            f.parentFile?.mkdirs(); f.writeText(svgText)
            ctx.state.readStamps[ctx.project.rel(f)] = f.lastModified()
            val preview = runCatching { render(SVG.getFromString(vectorToSvg(svgText)), 384) }.getOrNull()
            return ToolResult("wrote ${ctx.project.rel(f)} (${f.length()} B): vector drawable as given — use painterResource(R.drawable.${f.nameWithoutExtension}). " +
                (if (preview != null) "The image is a rendered preview." else "(No preview: only its paths are drawn for previews.)"),
                listOfNotNull(preview), summary = "Drew ${f.name}")
        }
        val svg = runCatching { SVG.getFromString(svgText) }.getOrElse { return ToolResult.error("invalid SVG: ${it.message}") }
        f.parentFile?.mkdirs()
        val note: String
        when (f.extension.lowercase()) {
            "xml" -> {
                if (!rel.replace('\\', '/').startsWith("res/drawable")) return ToolResult.error("vector drawables go in res/drawable/<name>.xml")
                val (xml, skipped) = runCatching { SvgToVector.convert(svgText) }.getOrElse { return ToolResult.error("can't convert to a vector drawable: ${it.message}") }
                f.writeText(xml)
                note = "vector drawable — use painterResource(R.drawable.${f.nameWithoutExtension})" +
                    (if (skipped.isNotEmpty()) ". Not supported in vector drawables, left out: ${skipped.joinToString()}" else "")
            }
            "png" -> { f.writeBytes(render(svg, size)); note = "PNG ${size}px wide" }
            "svg" -> { f.writeText(svgText); note = "SVG — show it with KSvg(\"file:///android_asset/${f.name}\", \"…\")" }
            else -> return ToolResult.error("path must end in .xml (res/drawable), .png or .svg")
        }
        ctx.state.readStamps[ctx.project.rel(f)] = f.lastModified()
        return ToolResult("wrote ${ctx.project.rel(f)} (${f.length()} B): $note. The image is the rendered preview.",
            listOf(render(svg, 384)), summary = "Drew ${f.name}")
    }

    private fun render(svg: SVG, width: Int): ByteArray {
        val vb = svg.documentViewBox
        val aspect = if (vb != null && vb.width() > 0) vb.height() / vb.width() else 1f
        val h = (width * aspect).toInt().coerceIn(1, 4096)
        svg.documentWidth = width.toFloat(); svg.documentHeight = h.toFloat()
        val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(Canvas(bmp))
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}

/** `xmlns="android=…"` / `xmlns=android=…` written for `xmlns:android="…"` (a common slip) → the real thing. */
internal fun repairNamespaces(xml: String): String =
    xml.replace(Regex("""xmlns\s*=\s*"?android\s*=\s*"""), "xmlns:android=")

internal fun isVectorDrawable(xml: String): Boolean = Regex("""^\s*(<\?xml[^>]*>\s*)?(<!--.*?-->\s*)*<vector\b""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(xml)

/** Where and why [xml] doesn't parse ("line 1, column 31: …"), or null when it does. */
internal fun xmlProblem(xml: String): String? = try {
    DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().apply { setErrorHandler(null) }
        .parse(org.xml.sax.InputSource(java.io.StringReader(xml))); null
} catch (e: org.xml.sax.SAXParseException) {
    val line = xml.lines().getOrNull(e.lineNumber - 1)?.trim()?.take(120)
    "line ${e.lineNumber}, column ${e.columnNumber}: ${e.message}" + (line?.let { "\n  > $it" } ?: "")
} catch (e: Exception) { e.message ?: "unreadable XML" }

/** A rough SVG of a vector drawable's paths (fill, stroke, alpha), only to render a preview of it. */
internal fun vectorToSvg(vector: String): String {
    val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(vector)))
    val root = doc.documentElement
    fun a(e: Element, n: String) = e.getAttribute("android:$n").ifEmpty { e.getAttribute(n) }
    // #AARRGGBB → (#RRGGBB, alpha); #RGB / #RRGGBB as they are.
    fun color(c: String): Pair<String, Double>? = when {
        c.isEmpty() -> null
        c.length == 9 && c.startsWith("#") -> "#" + c.substring(3) to c.substring(1, 3).toInt(16) / 255.0
        c.startsWith("#") -> c to 1.0
        else -> null
    }
    val vw = a(root, "viewportWidth").ifEmpty { "24" }; val vh = a(root, "viewportHeight").ifEmpty { "24" }
    val paths = root.getElementsByTagName("path")
    val body = (0 until paths.length).joinToString("\n") { i ->
        val p = paths.item(i) as Element
        val fill = color(a(p, "fillColor")); val stroke = color(a(p, "strokeColor"))
        buildString {
            append("<path d=\"").append(a(p, "pathData")).append('"')
            append(" fill=\"").append(fill?.first ?: "none").append('"')
            if (fill != null && fill.second < 1) append(" fill-opacity=\"").append(fill.second).append('"')
            if (stroke != null) {
                append(" stroke=\"").append(stroke.first).append("\" stroke-width=\"").append(a(p, "strokeWidth").ifEmpty { "1" }).append('"')
                a(p, "strokeLineCap").takeIf { it.isNotEmpty() }?.let { append(" stroke-linecap=\"").append(it).append('"') }
            }
            append("/>")
        }
    }
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $vw $vh\">\n$body\n</svg>"
}

/** SVG → Android VectorDrawable XML, for the subset vector drawables can express. */
object SvgToVector {
    private const val ANDROID = "http://schemas.android.com/apk/res/android"

    /** Returns the XML and the SVG features that had to be left out. */
    fun convert(svgText: String): Pair<String, List<String>> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder()
            .parse(svgText.byteInputStream())
        val root = doc.documentElement
        require(root.tagName.substringAfter(':') == "svg") { "root element must be <svg>" }
        val vb = root.getAttribute("viewBox").trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
        val vw = vb.getOrNull(2) ?: num(root.getAttribute("width")) ?: 24f
        val vh = vb.getOrNull(3) ?: num(root.getAttribute("height")) ?: 24f
        val ox = vb.getOrNull(0) ?: 0f; val oy = vb.getOrNull(1) ?: 0f
        val gradients = HashMap<String, Element>()
        collect(root) { if (it.tagName.endsWith("Gradient")) gradients[it.getAttribute("id")] = it }
        val skipped = linkedSetOf<String>()
        val body = StringBuilder()
        val dpW = 24f; val dpH = dpW * vh / vw
        val wrap = ox != 0f || oy != 0f
        if (wrap) body.append("  <group android:translateX=\"${f(-ox)}\" android:translateY=\"${f(-oy)}\">\n")
        children(root).forEach { emit(it, Style(), body, if (wrap) "    " else "  ", gradients, skipped) }
        if (wrap) body.append("  </group>\n")
        val xml = """<vector xmlns:android="$ANDROID" xmlns:aapt="http://schemas.android.com/aapt"
    android:width="${f(if (vw >= vh) dpW else dpW * vw / vh)}dp" android:height="${f(if (vw >= vh) dpH else dpW)}dp"
    android:viewportWidth="${f(vw)}" android:viewportHeight="${f(vh)}">
$body</vector>
"""
        return xml to skipped.toList()
    }

    private data class Style(val fill: String? = "#000000", val stroke: String? = null, val strokeWidth: Float = 1f,
                             val opacity: Float = 1f, val fillOpacity: Float = 1f, val strokeOpacity: Float = 1f,
                             val cap: String? = null, val join: String? = null, val evenOdd: Boolean = false)

    private fun styleOf(e: Element, parent: Style): Style {
        val a = HashMap<String, String>()
        for (k in listOf("fill", "stroke", "stroke-width", "opacity", "fill-opacity", "stroke-opacity", "stroke-linecap", "stroke-linejoin", "fill-rule"))
            e.getAttribute(k).takeIf { it.isNotBlank() }?.let { a[k] = it.trim() }
        e.getAttribute("style").split(';').forEach { d -> val kv = d.split(':', limit = 2); if (kv.size == 2) a[kv[0].trim()] = kv[1].trim() }
        return parent.copy(
            fill = a["fill"]?.let { if (it == "none") null else it } ?: parent.fill,
            stroke = a["stroke"]?.let { if (it == "none") null else it } ?: parent.stroke,
            strokeWidth = a["stroke-width"]?.let(::num) ?: parent.strokeWidth,
            opacity = parent.opacity * (a["opacity"]?.toFloatOrNull() ?: 1f),
            fillOpacity = a["fill-opacity"]?.toFloatOrNull() ?: parent.fillOpacity,
            strokeOpacity = a["stroke-opacity"]?.toFloatOrNull() ?: parent.strokeOpacity,
            cap = a["stroke-linecap"] ?: parent.cap, join = a["stroke-linejoin"] ?: parent.join,
            evenOdd = a["fill-rule"]?.let { it == "evenodd" } ?: parent.evenOdd)
    }

    private fun emit(e: Element, parent: Style, out: StringBuilder, ind: String, grads: Map<String, Element>, skipped: MutableSet<String>) {
        val tag = e.tagName.substringAfter(':')
        val st = styleOf(e, parent)
        val transform = e.getAttribute("transform").takeIf { it.isNotBlank() }
        if (tag in setOf("defs", "title", "desc", "metadata", "linearGradient", "radialGradient", "style")) return
        if (transform != null && tag != "g") {
            out.append(ind).append("<group").append(groupAttrs(transform, skipped)).append(">\n")
            emitShape(e, tag, st, out, "$ind  ", grads, skipped)
            out.append(ind).append("</group>\n"); return
        }
        if (tag == "g" || tag == "svg") {
            out.append(ind).append("<group").append(transform?.let { groupAttrs(it, skipped) } ?: "").append(">\n")
            children(e).forEach { emit(it, st, out, "$ind  ", grads, skipped) }
            out.append(ind).append("</group>\n"); return
        }
        emitShape(e, tag, st, out, ind, grads, skipped)
    }

    private fun emitShape(e: Element, tag: String, st: Style, out: StringBuilder, ind: String, grads: Map<String, Element>, skipped: MutableSet<String>) {
        fun a(n: String) = num(e.getAttribute(n)) ?: 0f
        val d = when (tag) {
            "path" -> e.getAttribute("d")
            "rect" -> {
                val x = a("x"); val y = a("y"); val w = a("width"); val h = a("height")
                var rx = num(e.getAttribute("rx")) ?: num(e.getAttribute("ry")) ?: 0f; var ry = num(e.getAttribute("ry")) ?: rx
                rx = rx.coerceAtMost(w / 2); ry = ry.coerceAtMost(h / 2)
                if (rx <= 0f) "M${f(x)},${f(y)}h${f(w)}v${f(h)}h${f(-w)}z"
                else "M${f(x + rx)},${f(y)}h${f(w - 2 * rx)}a${f(rx)},${f(ry)} 0 0 1 ${f(rx)},${f(ry)}v${f(h - 2 * ry)}" +
                    "a${f(rx)},${f(ry)} 0 0 1 ${f(-rx)},${f(ry)}h${f(-(w - 2 * rx))}a${f(rx)},${f(ry)} 0 0 1 ${f(-rx)},${f(-ry)}" +
                    "v${f(-(h - 2 * ry))}a${f(rx)},${f(ry)} 0 0 1 ${f(rx)},${f(-ry)}z"
            }
            "circle", "ellipse" -> {
                val cx = a("cx"); val cy = a("cy")
                val rx = if (tag == "circle") a("r") else a("rx"); val ry = if (tag == "circle") a("r") else a("ry")
                "M${f(cx - rx)},${f(cy)}a${f(rx)},${f(ry)} 0 1 0 ${f(2 * rx)},0a${f(rx)},${f(ry)} 0 1 0 ${f(-2 * rx)},0z"
            }
            "line" -> "M${f(a("x1"))},${f(a("y1"))}L${f(a("x2"))},${f(a("y2"))}"
            "polyline", "polygon" -> {
                val p = e.getAttribute("points").trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }.chunked(2).filter { it.size == 2 }
                if (p.isEmpty()) "" else "M" + p.joinToString(" L") { "${f(it[0])},${f(it[1])}" } + if (tag == "polygon") "z" else ""
            }
            else -> { skipped += "<$tag>"; return }
        }
        if (d.isBlank()) return
        val sb = StringBuilder(ind).append("<path android:pathData=\"").append(d.replace("\n", " ").trim()).append('"')
        var fillGradient: Element? = null
        st.fill?.let { fill ->
            val ref = Regex("url\\(#([^)]+)\\)").find(fill)?.groupValues?.get(1)
            if (ref != null) fillGradient = grads[ref] ?: run { skipped += "fill $fill"; null }
            else color(fill)?.let { sb.append(" android:fillColor=\"").append(it).append('"') } ?: skipped.add("colour $fill")
            if (st.fillOpacity * st.opacity < 1f) sb.append(" android:fillAlpha=\"").append(f(st.fillOpacity * st.opacity)).append('"')
        }
        st.stroke?.let { s ->
            color(s)?.let { sb.append(" android:strokeColor=\"").append(it).append('"') } ?: skipped.add("stroke $s")
            sb.append(" android:strokeWidth=\"").append(f(st.strokeWidth)).append('"')
            if (st.strokeOpacity * st.opacity < 1f) sb.append(" android:strokeAlpha=\"").append(f(st.strokeOpacity * st.opacity)).append('"')
            st.cap?.let { sb.append(" android:strokeLineCap=\"").append(it).append('"') }
            st.join?.let { sb.append(" android:strokeLineJoin=\"").append(it).append('"') }
        }
        if (st.evenOdd) sb.append(" android:fillType=\"evenOdd\"")
        val g = fillGradient
        if (g == null) { out.append(sb).append("/>\n"); return }
        out.append(sb).append(">\n").append(ind).append("  <aapt:attr name=\"android:fillColor\">\n")
        out.append(gradientXml(g, grads, "$ind    ")).append(ind).append("  </aapt:attr>\n").append(ind).append("</path>\n")
    }

    private fun gradientXml(g0: Element, grads: Map<String, Element>, ind: String): String {
        // Stops may come from a gradient this one links to with href.
        var g = g0
        val href = (g.getAttribute("href").ifBlank { g.getAttribute("xlink:href") }).removePrefix("#")
        val stopsFrom = if (children(g).none { it.tagName == "stop" } && href.isNotBlank()) grads[href] ?: g else g
        fun a(n: String, def: Float) = g.getAttribute(n).let { v -> if (v.endsWith("%")) null else num(v) } ?: def
        val sb = StringBuilder(ind)
        if (g.tagName.startsWith("radial")) sb.append("<gradient android:type=\"radial\" android:centerX=\"${f(a("cx", 0f))}\" android:centerY=\"${f(a("cy", 0f))}\" android:gradientRadius=\"${f(a("r", 1f))}\"")
        else sb.append("<gradient android:type=\"linear\" android:startX=\"${f(a("x1", 0f))}\" android:startY=\"${f(a("y1", 0f))}\" android:endX=\"${f(a("x2", 1f))}\" android:endY=\"${f(a("y2", 0f))}\"")
        sb.append(">\n")
        children(stopsFrom).filter { it.tagName == "stop" }.forEach { s ->
            val style = s.getAttribute("style").split(';').associate { it.substringBefore(':').trim() to it.substringAfter(':', "").trim() }
            val c = color(s.getAttribute("stop-color").ifBlank { style["stop-color"] ?: "#000" }) ?: "#FF000000"
            val op = (s.getAttribute("stop-opacity").ifBlank { style["stop-opacity"] ?: "1" }).toFloatOrNull() ?: 1f
            val off = s.getAttribute("offset").let { if (it.endsWith("%")) (it.dropLast(1).toFloatOrNull() ?: 0f) / 100 else it.toFloatOrNull() ?: 0f }
            sb.append(ind).append("  <item android:offset=\"${f(off)}\" android:color=\"${withAlpha(c, op)}\"/>\n")
        }
        g = g0
        return sb.append(ind).append("</gradient>\n").toString()
    }

    private fun groupAttrs(t: String, skipped: MutableSet<String>): String {
        val sb = StringBuilder()
        Regex("(\\w+)\\(([^)]*)\\)").findAll(t).forEach { m ->
            val v = m.groupValues[2].trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
            when (m.groupValues[1]) {
                "translate" -> sb.append(" android:translateX=\"${f(v.getOrElse(0) { 0f })}\" android:translateY=\"${f(v.getOrElse(1) { 0f })}\"")
                "scale" -> sb.append(" android:scaleX=\"${f(v.getOrElse(0) { 1f })}\" android:scaleY=\"${f(v.getOrElse(1) { v.getOrElse(0) { 1f } })}\"")
                "rotate" -> { sb.append(" android:rotation=\"${f(v.getOrElse(0) { 0f })}\"")
                    if (v.size >= 3) sb.append(" android:pivotX=\"${f(v[1])}\" android:pivotY=\"${f(v[2])}\"") }
                else -> skipped += "transform ${m.groupValues[1]}()"
            }
        }
        return sb.toString()
    }

    private val NAMED = mapOf("black" to "#000000", "white" to "#FFFFFF", "red" to "#FF0000", "green" to "#008000", "blue" to "#0000FF",
        "yellow" to "#FFFF00", "orange" to "#FFA500", "purple" to "#800080", "gray" to "#808080", "grey" to "#808080", "pink" to "#FFC0CB",
        "brown" to "#A52A2A", "cyan" to "#00FFFF", "magenta" to "#FF00FF", "navy" to "#000080", "teal" to "#008080", "gold" to "#FFD700",
        "silver" to "#C0C0C0", "lime" to "#00FF00", "maroon" to "#800000", "olive" to "#808000", "indigo" to "#4B0082", "violet" to "#EE82EE",
        "transparent" to "#00000000", "currentColor" to "#000000")

    private fun color(c0: String): String? {
        val c = c0.trim()
        NAMED[c]?.let { return it }
        if (c.startsWith("#")) return when (c.length) {
            4 -> "#" + c.drop(1).map { "$it$it" }.joinToString(""); 7, 9 -> c.uppercase(); else -> null }
        Regex("rgba?\\(([^)]*)\\)").find(c)?.let { m ->
            val p = m.groupValues[1].split(',').map { it.trim() }
            fun ch(s: String) = (if (s.endsWith("%")) s.dropLast(1).toFloat() * 2.55f else s.toFloat()).toInt().coerceIn(0, 255)
            val rgb = "%02X%02X%02X".format(ch(p[0]), ch(p[1]), ch(p[2]))
            val alpha = p.getOrNull(3)?.toFloatOrNull()
            return if (alpha == null) "#$rgb" else "#%02X".format((alpha * 255).toInt().coerceIn(0, 255)) + rgb
        }
        return null
    }

    private fun withAlpha(c: String, op: Float): String {
        if (op >= 1f) return c
        val rgb = c.takeLast(6)
        return "#%02X".format((op * 255).toInt().coerceIn(0, 255)) + rgb
    }

    private fun num(s: String?): Float? = s?.trim()?.removeSuffix("px")?.removeSuffix("dp")?.toFloatOrNull()
    private fun f(v: Float): String = if (v == v.toLong().toFloat()) v.toLong().toString() else "%.3f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
    private fun children(e: Element): List<Element> = (0 until e.childNodes.length).map { e.childNodes.item(it) }.filter { it.nodeType == Node.ELEMENT_NODE }.map { it as Element }
    private fun collect(e: Element, fn: (Element) -> Unit) { fn(e); children(e).forEach { collect(it, fn) } }
}
