package app.kiln.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.kiln.Graph
import app.kiln.build.BuildEngine
import app.kiln.build.ProjectMeta
import app.kiln.build.Signing
import app.kiln.core.KJPretty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Shipping: release builds, sharing them, and backing up the signing key.

private fun exports(): File = File(Graph.app.cacheDir, "exports").apply { mkdirs() }

/** Set the version, build a release APK or bundle, and stage it for sharing. */
suspend fun KilnVM.buildRelease(name: String, kind: BuildEngine.Kind, versionName: String, versionCode: Int,
                                onStep: (String) -> Unit): Result<File> = withContext(Dispatchers.IO) {
    runCatching {
        val s = state(name)
        check(s.loop.value?.running?.value != true) { "Kiln is working on this app — wait for it to finish" }
        val meta = s.project.meta()
        if (meta.versionName != versionName || meta.versionCode != versionCode)
            s.project.metaFile.writeText(KJPretty.encodeToString(ProjectMeta.serializer(), meta.copy(versionName = versionName, versionCode = versionCode)))
        val r = Graph.builds.build(s.project, kind = kind, onStep = onStep)
        check(r.ok) { r.errors.firstOrNull()?.let { "${it.file ?: ""}${it.line?.let { l -> ":$l" } ?: ""} ${it.message}".trim() } ?: "build failed" }
        val out = File(r.apk!!)
        out.copyTo(File(exports(), out.name), overwrite = true)
    }
}

/**
 * The signing key and its password in one zip. Every update to a published app must be signed
 * with this key (or registered with Play App Signing), so losing it means losing the app.
 */
suspend fun KilnVM.keyBackup(name: String): Result<File> = withContext(Dispatchers.IO) {
    runCatching {
        val s = state(name)
        val key = Signing.ensure(s.project)
        val nl = System.lineSeparator()
        val out = File(exports(), "${s.project.name}-signing-key.zip")
        ZipOutputStream(out.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("signing.p12")); z.write(key.keystore.readBytes()); z.closeEntry()
            z.putNextEntry(ZipEntry("README.txt"))
            z.write(("Signing key for ${s.label} (${s.pkg})" + nl + nl +
                "Keystore: signing.p12 (PKCS12)" + nl + "Alias: ${key.alias}" + nl + "Password: ${key.password}" + nl + nl +
                "Keep this private. Updates to the app must be signed with this key." + nl +
                "To restore it, put signing.p12 in the project's .kiln folder and the password in .kiln/signing.pass." + nl).toByteArray())
            z.closeEntry()
        }
        out
    }
}

fun shareFile(ctx: Context, f: File, mime: String, title: String) {
    val uri = FileProvider.getUriForFile(ctx, "app.kiln.files", f)
    // ClipData carries the read grant to the chooser too (its preview reads the file).
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = android.content.ClipData.newRawUri(f.name, uri)
    ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}
