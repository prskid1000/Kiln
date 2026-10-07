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

// Google Play and GitHub. Their credentials live in the Keystore-backed secrets store, never in
// project files, settings, transcripts or logs.

private const val PLAY_KEY = "play_service_account"
private const val GITHUB_TOKEN = "github_token"

fun KilnVM.playAccount(): String? = Graph.secrets.get(PLAY_KEY)?.let { runCatching { app.kiln.publish.PlayPublisher(it).email }.getOrNull() }

/** Keep a service-account key; returns its email, or an error. */
fun KilnVM.savePlayKey(json: String?): Result<String?> = runCatching {
    if (json == null) { Graph.secrets.put(PLAY_KEY, null); return@runCatching null }
    val email = app.kiln.publish.PlayPublisher(json).email
    Graph.secrets.put(PLAY_KEY, json); email
}

/** Build a bundle with the given version and release it to Play's internal testing track. */
suspend fun KilnVM.uploadToPlay(name: String, versionName: String, versionCode: Int, notes: String, onStep: (String) -> Unit): Result<String> {
    val json = Graph.secrets.get(PLAY_KEY) ?: return Result.failure(IllegalStateException("Add a service-account key first"))
    val aab = buildRelease(name, BuildEngine.Kind.RELEASE_AAB, versionName, versionCode, onStep).getOrElse { return Result.failure(it) }
    return withContext(Dispatchers.IO) {
        runCatching {
            val code = app.kiln.publish.PlayPublisher(json).upload(state(name).pkg, aab, "internal", notes, onStep)
            "Version $code is on the internal testing track"
        }
    }
}

suspend fun KilnVM.githubLogin(): String? = withContext(Dispatchers.IO) {
    Graph.secrets.get(GITHUB_TOKEN)?.let { runCatching { app.kiln.publish.GitHubSync(it).login() }.getOrNull() }
}

suspend fun KilnVM.saveGithubToken(token: String?): Result<String?> = withContext(Dispatchers.IO) {
    runCatching {
        if (token.isNullOrBlank()) { Graph.secrets.put(GITHUB_TOKEN, null); return@runCatching null }
        val login = app.kiln.publish.GitHubSync(token.trim()).login()
        Graph.secrets.put(GITHUB_TOKEN, token.trim()); login
    }
}

/** The repository this project syncs to ("owner/name"), kept in .kiln (which is never pushed). */
fun KilnVM.githubRepo(name: String): String =
    runCatching { File(state(name).project.kilnDir, "github.txt").readText().trim() }.getOrDefault("")

fun KilnVM.setGithubRepo(name: String, repo: String) {
    val p = state(name).project
    p.kilnDir.mkdirs(); File(p.kilnDir, "github.txt").writeText(repo.trim())
}

suspend fun KilnVM.createGithubRepo(name: String): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        val token = Graph.secrets.get(GITHUB_TOKEN) ?: error("Add a GitHub token first")
        val s = state(name)
        app.kiln.publish.GitHubSync(token).createRepo(s.project.name.replace('_', '-'), "${s.label} — an Android app built with Kiln")
            .also { setGithubRepo(name, it) }
    }
}

suspend fun KilnVM.pushToGithub(name: String, message: String, onStep: (String) -> Unit): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        val token = Graph.secrets.get(GITHUB_TOKEN) ?: error("Add a GitHub token first")
        val repo = githubRepo(name).ifBlank { error("Set the repository first") }
        app.kiln.publish.GitHubSync(token).push(state(name).project, repo, message.ifBlank { "Update from Kiln" }, onStep)
            ?.let { "Pushed: $it" } ?: "Nothing changed since the last push"
    }
}

/** What the agent is asked to do for "Prepare store listing". */
const val STORE_LISTING_PROMPT = "Prepare this app's Google Play listing in store/ (don't change the app's code; if the review " +
    "finds a real problem, describe the fix and ask me first):\n" +
    "1. store/listing.md — App name (≤ 30 chars), Short description (≤ 80), Full description (≤ 4000, plain text, " +
    "benefit-led, no keyword stuffing), Category, and tags.\n" +
    "2. Run the app, fill it with realistic sample content, and save 4–6 screenshots of its main screens with " +
    "save_screenshot to store/screenshots/ (1-…png, 2-…png in story order). No debug text or empty states unless that's the point.\n" +
    "3. store/policy.md — a Play policy review: every permission and why it's needed, Data safety answers (what's " +
    "collected or shared, encrypted in transit, can users delete it), target audience, ads, and anything likely to " +
    "get the app rejected.\nThen summarise what's ready and what I still need to do in Play Console."

/** A 1024×500 feature graphic from the app's icon and name, saved as store/feature-graphic.png. */
suspend fun KilnVM.featureGraphic(name: String): Result<File> = withContext(Dispatchers.IO) {
    runCatching {
        val s = state(name)
        // From the built APK: Kiln can't look other apps up by package name (package visibility).
        val apk = File(s.project.buildDir, "${s.pkg}.apk")
        val icon = runCatching {
            val pm = Graph.app.packageManager
            val info = pm.getPackageArchiveInfo(apk.path, 0)!!.applicationInfo!!
            info.sourceDir = apk.path; info.publicSourceDir = apk.path
            info.loadIcon(pm)
        }.getOrNull() ?: error("Run the app once first — the icon comes from its build")
        val w = 1024; val h = 500
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawPaint(android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
                android.graphics.Color.rgb(46, 38, 92), android.graphics.Color.rgb(18, 20, 33), android.graphics.Shader.TileMode.CLAMP)
        })
        val side = 260
        // Clipped like a launcher icon: an adaptive icon's layers are full-bleed squares.
        val top = (h - side) / 2f
        c.save()
        c.clipPath(android.graphics.Path().apply { addRoundRect(96f, top, 96f + side, top + side, 64f, 64f, android.graphics.Path.Direction.CW) })
        icon.setBounds(96, top.toInt(), 96 + side, top.toInt() + side); icon.draw(c)
        c.restore()
        val title = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE; textSize = 68f; typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val x = 96f + side + 64f; val maxW = w - x - 64f
        var label = s.label
        while (title.measureText(label) > maxW && title.textSize > 36f) title.textSize -= 2f
        while (title.measureText(label) > maxW && label.length > 4) label = label.dropLast(2) + "…"
        c.drawText(label, x, h / 2f + title.textSize / 3f, title)
        val out = s.project.resolveWritable("store/feature-graphic.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        out
    }
}

fun KilnVM.storeFiles(name: String): List<String> {
    val p = state(name).project
    return File(p.dir, "store").takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile }?.map { p.rel(it) }?.sorted()?.toList() ?: emptyList()
}
