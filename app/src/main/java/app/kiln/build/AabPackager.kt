package app.kiln.build

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * An Android App Bundle (.aab) for Play, without bundletool: the base module laid out the way
 * bundletool writes it, from aapt2's proto-format link output plus the dex and assets.
 *
 *   BundleConfig.pb
 *   base/manifest/AndroidManifest.xml   (proto XML)
 *   base/resources.pb, base/res/…
 *   base/dex/classes*.dex
 *   base/assets/…, base/root/… (other files from the link output)
 */
object AabPackager {
    /** The bundletool version Play reads from BundleConfig.pb. */
    const val BUNDLETOOL_VERSION = "1.17.2"

    fun pack(protoRes: File, dexFiles: List<File>, assetsDir: File?, out: File) {
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered(1 shl 16)).use { z ->
            fun put(name: String, data: ByteArray) {
                z.putNextEntry(ZipEntry(name).apply { time = 1199145600000L }); z.write(data); z.closeEntry()
            }
            put("BundleConfig.pb", bundleConfig())
            ZipFile(protoRes).use { zf ->
                for (e in zf.entries()) {
                    if (e.isDirectory) continue
                    val data = zf.getInputStream(e).use { it.readBytes() }
                    put(when {
                        e.name == "AndroidManifest.xml" -> "base/manifest/AndroidManifest.xml"
                        e.name == "resources.pb" -> "base/resources.pb"
                        e.name.startsWith("res/") -> "base/" + e.name
                        else -> "base/root/" + e.name
                    }, data)
                }
            }
            dexFiles.forEachIndexed { i, dex -> put("base/dex/" + (if (i == 0) "classes.dex" else "classes${i + 1}.dex"), dex.readBytes()) }
            if (assetsDir != null && assetsDir.isDirectory) {
                assetsDir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                    put("base/assets/" + f.relativeTo(assetsDir).invariantSeparatorsPath, f.readBytes())
                }
            }
        }
    }

    /** BundleConfig { bundletool { version: "…" } } — field 1 (message) holding field 2 (string). */
    fun bundleConfig(): ByteArray {
        val v = BUNDLETOOL_VERSION.toByteArray()
        val inner = byteArrayOf(0x12, v.size.toByte()) + v
        return byteArrayOf(0x0A, inner.size.toByte()) + inner
    }
}
