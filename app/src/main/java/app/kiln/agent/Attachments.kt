package app.kiln.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.kiln.build.Project
import app.kiln.core.obj
import kotlinx.serialization.json.JsonElement
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/** A file the user attached to a chat message. */
class Attachment(val name: String, val mime: String, val bytes: ByteArray)

/**
 * How attachments reach the model. Every file is also saved in the project under
 * `attachments/` so the agent can use it (move it into assets/ or res/, read it again later).
 * - images: an image block (downscaled; models don't need more than ~1600 px)
 * - text/code (UTF-8, ≤ 200 KB): inline, wrapped in an <attachment> tag
 * - anything else: a note with its saved path, size and type
 */
object Attachments {
    const val DIR = "attachments"
    private const val MAX_TEXT = 200_000
    private const val MAX_SIDE = 1568
    private val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

    /** Saves each attachment into the project; returns its project-relative path. */
    fun save(project: Project, a: Attachment): String {
        val dir = File(project.dir, DIR).apply { mkdirs() }
        val clean = a.name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "file" }
        var f = File(dir, clean); var i = 2
        while (f.exists()) { f = File(dir, clean.substringBeforeLast('.') + "_$i" + clean.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }); i++ }
        f.writeBytes(a.bytes)
        return project.rel(f)
    }

    fun blocks(project: Project, list: List<Attachment>): List<JsonElement> = list.map { a ->
        val path = save(project, a)
        val kb = (a.bytes.size + 1023) / 1024
        when {
            a.mime in IMAGE_TYPES || a.mime.startsWith("image/") -> image(a)?.let { (mime, data) ->
                obj("type" to "image", "source" to obj("type" to "base64", "media_type" to mime, "data" to data))
            } ?: note(path, a, kb)
            isText(a) -> obj("type" to "text", "text" to
                "<attachment name=\"${a.name}\" path=\"$path\">\n${a.bytes.decodeToString()}\n</attachment>")
            else -> note(path, a, kb)
        }
    }

    private fun note(path: String, a: Attachment, kb: Int) = obj("type" to "text", "text" to
        "<attachment name=\"${a.name}\" path=\"$path\" type=\"${a.mime}\" size=\"$kb KB\">(binary file saved in the project)</attachment>")

    private fun isText(a: Attachment): Boolean {
        if (a.bytes.size > MAX_TEXT) return false
        if (a.mime.startsWith("text/") || a.mime in setOf("application/json", "application/xml", "application/x-kotlin")) return true
        val head = a.bytes.take(4096)
        return head.none { it == 0.toByte() } && runCatching { java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(a.bytes)) }.isSuccess
    }

    /** (media type, base64), re-encoded as JPEG/PNG and capped at [MAX_SIDE]. */
    private fun image(a: Attachment): Pair<String, String>? {
        // Decoded at a fraction of its size first: a 50 MP photo at full size needed ~200 MB and failed the send.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(a.bytes, 0, a.bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(a.bytes, 0, a.bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val k = MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
        val out = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true) else bmp
        val png = a.mime == "image/png" && k >= 1f && sample == 1 && a.bytes.size < 1_500_000
        if (png) return "image/png" to Base64.getEncoder().encodeToString(a.bytes)
        val buf = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return "image/jpeg" to Base64.getEncoder().encodeToString(buf.toByteArray())
    }

    /** For the chat feed: name of an <attachment> text block, or null. */
    fun nameOf(text: String): String? =
        if (text.startsWith("<attachment ")) Regex("""name="([^"]*)"""").find(text)?.groupValues?.get(1) else null
}
