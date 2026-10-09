package app.kiln.build

import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Writes the unsigned APK: the linked resources from aapt2 (manifest,
 * resources.arsc, res/), the dex files, and assets/.
 *
 * Dex is stored uncompressed (see below), so a build is mostly copying.
 *
 * Alignment is done here, so there is no zipalign step: stored entries get
 * their data 4-byte aligned (16 KB for .so), padded through the local header's
 * extra field — exactly what zipalign does. resources.arsc must be stored and
 * aligned on targetSdk 30+, or the package manager refuses the APK.
 */
object ApkPackager {

    private class Counting(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    /** Extensions stored uncompressed (already compressed, or must be mmap-able). */
    private val storedExt = setOf("arsc", "png", "jpg", "jpeg", "webp", "gif", "mp3", "ogg", "mp4", "zip", "so")

    fun pack(resApk: File, dexFiles: List<File>, assetsDir: File?, out: File) {
        out.parentFile?.mkdirs()
        val counting = Counting(out.outputStream().buffered(1 shl 16))
        ZipOutputStream(counting).use { zos ->
            ZipFile(resApk).use { zf ->
                for (e in zf.entries()) {
                    if (e.isDirectory) continue
                    val data = zf.getInputStream(e).use { it.readBytes() }
                    put(zos, counting, e.name, data, stored = e.method == ZipEntry.STORED || e.name.substringAfterLast('.') in storedExt)
                }
            }
            // Dex stored uncompressed and aligned (AGP's default from minSdk 28): ART
            // maps it straight from the APK, and packaging is a copy, not a deflate.
            dexFiles.forEachIndexed { i, dex ->
                put(zos, counting, if (i == 0) "classes.dex" else "classes${i + 1}.dex", dex.readBytes(), stored = true)
            }
            if (assetsDir != null && assetsDir.isDirectory) {
                assetsDir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                    put(zos, counting, "assets/" + f.relativeTo(assetsDir).invariantSeparatorsPath, f.readBytes(),
                        stored = f.extension in storedExt)
                }
            }
        }
    }

    private fun put(zos: ZipOutputStream, counting: Counting, name: String, data: ByteArray, stored: Boolean) {
        val e = ZipEntry(name)
        // Fixed DOS-representable time: reproducible, and no extended-timestamp
        // extra field (which a pre-1980 time would add, breaking the padding).
        e.time = 1199145600000L   // 2008-01-01
        if (stored) {
            val crc = CRC32().apply { update(data) }
            e.method = ZipEntry.STORED
            e.size = data.size.toLong(); e.compressedSize = data.size.toLong(); e.crc = crc.value
            val align = if (name.endsWith(".so")) 16384 else 4
            // Local header = 30 bytes + name + extra; pad extra so the data lands aligned.
            val headerStart = counting.count
            val base = headerStart + 30 + name.toByteArray().size
            val pad = ((align - (base % align)) % align).toInt()
            e.extra = ByteArray(pad)
        } else {
            e.method = ZipEntry.DEFLATED
        }
        zos.putNextEntry(e)
        zos.write(data)
        zos.closeEntry()
    }
}
