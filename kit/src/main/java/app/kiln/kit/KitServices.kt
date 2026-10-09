package app.kiln.kit

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

// ---------------------------------------------------------------- database

/** A row of a [KCollection]: its id, and your value. */
data class KRow<T>(val id: Long, val value: T, val updatedAt: Long)

/**
 * A persisted collection of items (a small database table) backed by SQLite: add, update, delete,
 * query, and a live [rows] flow for the UI. Use it for lists that grow (notes, expenses, logs);
 * use [KStore] for one value (settings, a small list).
 * ```
 * @Serializable data class Note(val title: String, val body: String = "")
 * val notes = KCollection<Note>("notes")   // no Context needed (an overload takes one)
 * val rows by notes.rows.collectAsStateWithLifecycle()      // List<KRow<Note>>, newest first
 * val id = notes.add(Note("Hi")); notes.update(id, Note("Hello")); notes.delete(id)
 * notes.query { it.title.contains("hi", ignoreCase = true) }
 * ```
 */
// Constructed only through the shared registry: an instance outside it never saw its own writes.
class KCollection<T> @PublishedApi internal constructor(context: Context, private val name: String, private val serializer: KSerializer<T>,
                     private val newestFirst: Boolean = true) {
    private val db = KDb.get(context)
    private val table = "c_" + name.replace(Regex("[^A-Za-z0-9_]"), "_")
    private val flow = MutableStateFlow<List<KRow<T>>>(emptyList())
    /** Every row, kept current as you add, update and delete. */
    val rows: StateFlow<List<KRow<T>>> = flow.asStateFlow()
    /** Just the values, in the same order. */
    val items: List<T> get() = flow.value.map { it.value }

    init {
        db.writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS $table (id INTEGER PRIMARY KEY AUTOINCREMENT, json TEXT NOT NULL, updated INTEGER NOT NULL)")
        reload()
    }

    // Synchronized: a slower reload can't publish an older list over a newer one.
    @Synchronized private fun reload() {
        val out = mutableListOf<KRow<T>>()
        var unreadable = 0
        db.readableDatabase.rawQuery("SELECT id, json, updated FROM $table ORDER BY id ${if (newestFirst) "DESC" else "ASC"}", null).use { c ->
            while (c.moveToNext()) runCatching { out += KRow(c.getLong(0), KJson.decodeFromString(serializer, c.getString(1)), c.getLong(2)) }
                .onFailure { unreadable++ }
        }
        // Rows that no longer decode stay in the table (nothing is lost); they're just not shown.
        if (unreadable > 0) android.util.Log.w("KCollection", "$name: $unreadable row(s) couldn't be read with the current type")
        flow.value = out
    }

    /** After a write: every open collection on this table (newest-first and oldest-first) shows it. */
    private fun changed() { open.values.filter { (it as KCollection<*>).name == name }.forEach { it.reload() } }

    /** Add [value]; returns its id. */
    fun add(value: T): Long = db.writableDatabase.insert(table, null, ContentValues().apply {
        put("json", KJson.encodeToString(serializer, value)); put("updated", System.currentTimeMillis())
    }).also { changed() }

    /** Replace the item with [id]. */
    fun update(id: Long, value: T) {
        db.writableDatabase.update(table, ContentValues().apply {
            put("json", KJson.encodeToString(serializer, value)); put("updated", System.currentTimeMillis())
        }, "id = ?", arrayOf(id.toString())); changed()
    }

    /** Change the item with [id] from its current value. */
    fun modify(id: Long, change: (T) -> T) { get(id)?.let { update(id, change(it)) } }

    fun get(id: Long): T? = flow.value.firstOrNull { it.id == id }?.value
    fun delete(id: Long) { db.writableDatabase.delete(table, "id = ?", arrayOf(id.toString())); changed() }
    /** Put a deleted [row] back as it was — same id and time (Undo): add() would give it a new id and move it to the top. */
    fun restore(row: KRow<T>) {
        db.writableDatabase.insertWithOnConflict(table, null, ContentValues().apply {
            put("id", row.id); put("json", KJson.encodeToString(serializer, row.value)); put("updated", row.updatedAt)
        }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE); changed()
    }
    fun clear() { db.writableDatabase.delete(table, null, null); changed() }
    /** Items matching [predicate], as rows. */
    fun query(predicate: (T) -> Boolean): List<KRow<T>> = flow.value.filter { predicate(it.value) }
    val size: Int get() = flow.value.size

    companion object {
        /** One collection per name: every screen sees the same rows and updates. */
        @PublishedApi internal val open = java.util.concurrent.ConcurrentHashMap<String, KCollection<*>>()

        @Suppress("UNCHECKED_CAST")
        @PublishedApi internal fun <T> shared(context: Context, name: String, serializer: KSerializer<T>, newestFirst: Boolean): KCollection<T> =
            open.getOrPut("$name/$newestFirst") { KCollection(context, name, serializer, newestFirst) } as KCollection<T>

        inline operator fun <reified T> invoke(context: Context, name: String, newestFirst: Boolean = true): KCollection<T> =
            shared(context, name, KJson.serializersModule.serializer(), newestFirst)
        /** No Context needed: `val notes = KCollection<Note>("notes")` (uses [KApp.context]). */
        inline operator fun <reified T> invoke(name: String, newestFirst: Boolean = true): KCollection<T> =
            shared(KApp.context, name, KJson.serializersModule.serializer(), newestFirst)
    }
}

internal class KDb private constructor(context: Context) : SQLiteOpenHelper(context, "kiln.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {}
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}
    companion object {
        @Volatile private var instance: KDb? = null
        fun get(context: Context): KDb = instance ?: synchronized(this) { instance ?: KDb(context.applicationContext).also { instance = it } }
    }
}

// ---------------------------------------------------------------- state

/**
 * A persisted value as Compose state: survives restarts, typed, any @Serializable type.
 * `var count by rememberStored("count", 0)`; `var todos by rememberStored("todos", emptyList<Todo>())`.
 */
@Composable
inline fun <reified T> rememberStored(name: String, default: T): MutableState<T> {
    val context = LocalContext.current
    val store = remember(name) { KStore(context, name, default) }
    // Follows the store, so another screen (or Repo code) writing it shows up here too.
    val current = store.state.collectAsState()
    return remember(store) { KStoredState(store, current) }
}

/** The state behind [rememberStored]; writes go to its [KStore], reads follow it. */
class KStoredState<T>(private val store: KStore<T>, private val current: State<T>) : MutableState<T> {
    override var value: T
        // Read `current` to recompose on changes; return the store's value, which is fresh even right after a set.
        get() { current.value; return store.value }
        set(v) { store.set(v) }
    override fun component1(): T = value
    override fun component2(): (T) -> Unit = { value = it }
}

/**
 * Small typed settings (key → value) in SharedPreferences, as Compose state.
 * `var dark by rememberPref("dark", true)`; `var name by rememberPref("name", "")`.
 * Supports String, Int, Long, Float, Double and Boolean.
 */
@Composable
fun <T> rememberPref(key: String, default: T): MutableState<T> {
    val context = LocalContext.current
    return remember(key) { KPref(KPrefs.of(context), key, default) }
}

/** SharedPreferences access outside Compose: `KPrefs.of(ctx).getInt("runs", 0)`. */
object KPrefs {
    fun of(context: Context): SharedPreferences = context.getSharedPreferences("kiln_prefs", Context.MODE_PRIVATE)
}

private class KPref<T>(private val prefs: SharedPreferences, private val key: String, private val default: T) : MutableState<T> {
    @Suppress("UNCHECKED_CAST")
    // A key that holds another type (the app changed rememberPref("goal", 2000) to 2.5) starts over from the default
    // instead of crashing the screen on every launch.
    // Only a type clash is recovered: an unsupported type still fails loudly (recovering it silently lost every save).
    private fun read(): T = try { readTyped() } catch (e: ClassCastException) { prefs.edit().remove(key).apply(); default }

    @Suppress("UNCHECKED_CAST")
    private fun readTyped(): T = when (default) {
        is String -> prefs.getString(key, default) as T
        is Int -> prefs.getInt(key, default) as T
        is Long -> prefs.getLong(key, default) as T
        is Float -> prefs.getFloat(key, default) as T
        is Boolean -> prefs.getBoolean(key, default) as T
        // `2.0` is a Double in Kotlin (it crashed the screen): stored as raw bits under its own key, so a Long once stored
        // under the name isn't read as bits (70L showed as 3.46E-322).
        is Double -> java.lang.Double.longBitsToDouble(prefs.getLong("$key#d", java.lang.Double.doubleToRawLongBits(default))) as T
        else -> error("rememberPref supports String, Int, Long, Float, Double, Boolean — use rememberStored for other types")
    }
    private val inner = mutableStateOf(read())
    override var value: T
        get() = inner.value
        set(v) {
            inner.value = v
            prefs.edit().apply { when (v) { is String -> putString(key, v); is Int -> putInt(key, v); is Long -> putLong(key, v)
                is Float -> putFloat(key, v); is Boolean -> putBoolean(key, v)
                is Double -> putLong("$key#d", java.lang.Double.doubleToRawLongBits(v))
                else -> error("rememberPref supports String, Int, Long, Float, Double, Boolean — use rememberStored for other types") } }.apply()
        }
    override fun component1(): T = value
    override fun component2(): (T) -> Unit = { value = it }
}

// ---------------------------------------------------------------- storage

/**
 * Files in the app's private storage (no permission needed), and sharing them.
 * `KFiles.writeText(ctx, "notes/today.txt", text)`; `KFiles.readText(ctx, "notes/today.txt")`;
 * `KFiles.share(ctx, KFiles.file(ctx, "export.csv"), "text/csv")`.
 */
object KFiles {
    /** A file under the app's files dir (folders are created). */
    fun file(context: Context, path: String): File = File(context.filesDir, path).also { it.parentFile?.mkdirs() }
    /** A file under the cache dir (the system may clear it). */
    fun cacheFile(context: Context, path: String): File = File(context.cacheDir, path).also { it.parentFile?.mkdirs() }
    fun writeText(context: Context, path: String, text: String): Unit = file(context, path).writeText(text)
    fun readText(context: Context, path: String): String? = file(context, path).takeIf { it.isFile }?.readText()
    fun writeBytes(context: Context, path: String, bytes: ByteArray): Unit = file(context, path).writeBytes(bytes)
    fun readBytes(context: Context, path: String): ByteArray? = file(context, path).takeIf { it.isFile }?.readBytes()
    fun exists(context: Context, path: String): Boolean = File(context.filesDir, path).exists()
    fun delete(context: Context, path: String): Boolean = File(context.filesDir, path).deleteRecursively()
    /** Files directly in [folder] (relative to the files dir). */
    fun list(context: Context, folder: String = ""): List<File> = File(context.filesDir, folder).listFiles()?.sortedBy { it.name }?.toList() ?: emptyList()
    /** Read what a picker returned (a content:// Uri). */
    fun readUri(context: Context, uri: Uri): ByteArray? = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
    fun readUriText(context: Context, uri: Uri): String? = readUri(context, uri)?.decodeToString()
    /** Share a file with other apps (email, Drive, chat). */
    fun share(context: Context, file: File, mime: String = "application/octet-stream", title: String = "Share") {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".kiln.files", file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

/** Pick a document of [mimeTypes] (e.g. "text/csv", "application/pdf"; the default allows any type): `val pick = rememberFilePicker { uri -> }; pick()`. */
@Composable
fun rememberFilePicker(mimeTypes: Array<String> = arrayOf("*/*"), onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(onPicked) }
    return { launcher.launch(mimeTypes) }
}

/** Let the user choose where to save a new file: `val save = rememberFileSaver("text/csv") { uri -> write to it }; save("export.csv")`. */
@Composable
fun rememberFileSaver(mime: String, onCreated: (Uri) -> Unit): (String) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { it?.let(onCreated) }
    return { name -> launcher.launch(name) }
}

// ---------------------------------------------------------------- media

/** Pick photos from the gallery (no permission needed): `val pick = rememberImagePicker(max = 3) { uris -> }; pick()`. */
@Composable
fun rememberImagePicker(max: Int = 1, onPicked: (List<Uri>) -> Unit): () -> Unit {
    val one = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> onPicked(listOf(u)) } }
    // Clamped to what the system picker allows (a larger max, meant as "any number", crashed on pick).
    val limit = if (android.os.Build.VERSION.SDK_INT >= 33) android.provider.MediaStore.getPickImagesMaxLimit() else 100
    val many = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxOf(2, minOf(max, limit)))) { if (it.isNotEmpty()) onPicked(it) }
    val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    return { if (max <= 1) one.launch(request) else many.launch(request) }
}

/**
 * Take a quick photo with the camera app: `val shoot = rememberCameraShot { bitmap -> }; shoot()`. No permission is
 * needed — unless the app declares CAMERA, which must then be granted first. `shoot()` is false if the camera
 * couldn't open (no camera app, CAMERA not granted): tell the user. Call it inside a lambda —
 * `onClick = { noCamera = !shoot() }` — not as `onClick = shoot` (it returns a Boolean).
 */
@Composable
fun rememberCameraShot(onPhoto: (Bitmap) -> Unit): () -> Boolean {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { it?.let(onPhoto) }
    // Guarded: no camera app, or CAMERA declared but not granted, threw and crashed the app.
    return { runCatching { launcher.launch(null) }.onFailure { KLog.w("camera: ${it.message}") }.isSuccess }
}

/** Images and media helpers. */
object KMedia {
    /** Save [bitmap] to the user's Pictures (shows in the gallery); returns its Uri. */
    fun saveToGallery(context: Context, bitmap: Bitmap, name: String = "image_${System.currentTimeMillis()}"): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/" + context.applicationInfo.loadLabel(context.packageManager))
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return uri
    }
    /** Decode an image Uri (from a picker) to a Bitmap, scaled to at most [maxSide] px. */
    fun loadBitmap(context: Context, uri: Uri, maxSide: Int = 2048): Bitmap? = runCatching {
        val src = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
        android.graphics.ImageDecoder.decodeBitmap(src) { d, info, _ ->
            val s = maxOf(info.size.width, info.size.height); if (s > maxSide) d.setTargetSampleSize((s + maxSide - 1) / maxSide)   // round up: at most maxSide
            d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()
}

// ---------------------------------------------------------------- network

/**
 * HTTP with JSON: typed get/post/put/patch/delete, headers, timeouts and retries.
 * `val posts: List<Post> = KHttp.get("https://…/posts")`;
 * `val created: Post = KHttp.post("https://…/posts", NewPost("Hi"), headers = mapOf("Authorization" to "Bearer …"))`.
 * Errors throw [KHttpException] (with the status code) — catch it and show [KError].
 */
object KHttp {
    var timeoutSeconds: Long = 30

    private val client get() = KNet.client.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build()

    /**
     * Raw call: returns the body text. [retries] retries network failures and 5xx with backoff — by default only
     * for requests that are safe to repeat (GET/HEAD/PUT/DELETE): a retried POST after a timeout created the order twice.
     */
    suspend fun request(method: String, url: String, body: String? = null, headers: Map<String, String> = emptyMap(),
                        retries: Int = if (method == "POST" || method == "PATCH") 0 else 1): String = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            try {
                val rb = body?.toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url(url).method(method, if (method == "GET" || method == "HEAD") null else (rb ?: ByteArray(0).toRequestBody()))
                    .header("Accept", "application/json").apply { headers.forEach { (k, v) -> header(k, v) } }.build()
                client.newCall(req).execute().use { r ->
                    val text = r.body.string()
                    if (r.code >= 500 && attempt < retries) throw IOException("HTTP ${r.code}")
                    // A retried DELETE that finds nothing: the first attempt did it (its reply was lost).
                    if (method == "DELETE" && attempt > 0 && r.code == 404) return@withContext text
                    if (!r.isSuccessful) throw KHttpException(r.code, text.take(300))
                    return@withContext text
                }
            } catch (e: KHttpException) { throw e
            } catch (e: IOException) {
                if (attempt++ >= retries) throw e
                delay(500L * attempt)
            }
        }
        @Suppress("UNREACHABLE_CODE") ""
    }

    suspend inline fun <reified T> get(url: String, headers: Map<String, String> = emptyMap()): T =
        KJson.decodeFromString(KJson.serializersModule.serializer<T>(), request("GET", url, null, headers))
    suspend inline fun <reified B, reified T> post(url: String, body: B, headers: Map<String, String> = emptyMap()): T =
        KJson.decodeFromString(KJson.serializersModule.serializer<T>(), request("POST", url, KJson.encodeToString(KJson.serializersModule.serializer<B>(), body), headers))
    suspend inline fun <reified B, reified T> put(url: String, body: B, headers: Map<String, String> = emptyMap()): T =
        KJson.decodeFromString(KJson.serializersModule.serializer<T>(), request("PUT", url, KJson.encodeToString(KJson.serializersModule.serializer<B>(), body), headers))
    suspend inline fun <reified B, reified T> patch(url: String, body: B, headers: Map<String, String> = emptyMap()): T =
        KJson.decodeFromString(KJson.serializersModule.serializer<T>(), request("PATCH", url, KJson.encodeToString(KJson.serializersModule.serializer<B>(), body), headers))
    suspend fun delete(url: String, headers: Map<String, String> = emptyMap()) { request("DELETE", url, null, headers) }
}

/** An HTTP error status, with the start of the response body. */
class KHttpException(val code: Int, val body: String) : IOException("HTTP $code: $body")

/** Is the device online? `val online by rememberOnline()` updates as the network comes and goes. */
@Composable
fun rememberOnline(): State<Boolean> {
    val context = LocalContext.current
    return produceState(KNetwork.isOnline(context)) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { value = true }
            override fun onLost(network: Network) { value = KNetwork.isOnline(context) }
        }
        cm.registerDefaultNetworkCallback(cb)
        awaitDispose { cm.unregisterNetworkCallback(cb) }
    }
}

/** Network state outside Compose. */
object KNetwork {
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

// ---------------------------------------------------------------- intents

/** Hand off to other apps: phone, SMS, email, maps, calendar, settings, Play Store. Each returns false when no app can open it (say so to the user). */
object KOpen {
    private fun go(context: Context, intent: Intent) = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    /** Open the dialer with [number] filled in (no permission needed). */
    fun dial(context: Context, number: String): Boolean = go(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
    fun sms(context: Context, number: String, text: String = ""): Boolean = go(context, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", text))
    fun email(context: Context, to: String, subject: String = "", body: String = ""): Boolean =
        go(context, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body))
    /** Maps search for [query] (an address or "coffee near me"). */
    fun map(context: Context, query: String): Boolean = go(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query))))
    fun url(context: Context, url: String): Boolean = go(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    /** Add an event to the calendar app (the user confirms it there). */
    fun calendarEvent(context: Context, title: String, startMillis: Long, endMillis: Long, location: String = ""): Boolean =
        go(context, Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            .putExtra(android.provider.CalendarContract.Events.TITLE, title)
            .putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, location))
    /** This app's system settings page (permissions, notifications). */
    fun appSettings(context: Context): Boolean = go(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
    fun notificationSettings(context: Context): Boolean = go(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    fun wifiSettings(context: Context): Boolean = go(context, Intent(Settings.ACTION_WIFI_SETTINGS))
    /** This app's Play Store page (for "Rate us"). */
    fun playStore(context: Context, pkg: String = context.packageName): Boolean = go(context, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
    /** Share plain text. */
    fun shareText(context: Context, text: String, subject: String? = null): Boolean =
        go(context, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            .apply { subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) } }, null))
}

// ---------------------------------------------------------------- device

/** Copy and paste text. */
object KClipboard {
    fun copy(context: Context, text: String, label: String = "text"): Unit =
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    fun paste(context: Context): String? = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
}

/** Vibration feedback: `KHaptics.tick(ctx)` on taps, `success`/`error` after actions. */
object KHaptics {
    private fun v(context: Context) = context.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }
    // Haptics are a nicety: never let one crash the app (no vibrator, permission stripped).
    private inline fun safe(block: () -> Unit) { runCatching(block) }
    fun tick(context: Context): Unit = safe { v(context)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)) }
    fun click(context: Context): Unit = safe { v(context)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)) }
    fun heavy(context: Context): Unit = safe { v(context)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)) }
    fun success(context: Context): Unit = safe { v(context)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30, 60, 30), -1)) }
    fun error(context: Context): Unit = safe { v(context)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 60, 80), -1)) }
    fun buzz(context: Context, millis: Long = 300): Unit = safe { v(context)?.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE)) }
}

/** Facts about the device. */
object KDevice {
    val model: String get() = "${Build.MANUFACTURER} ${Build.MODEL}"
    val androidVersion: String get() = Build.VERSION.RELEASE
    /** Battery charge, 0–100. */
    fun batteryPercent(context: Context): Int = context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    fun isCharging(context: Context): Boolean = context.getSystemService(BatteryManager::class.java).isCharging
    fun locale(): Locale = Locale.getDefault()
}

/** Read text aloud: `val speak = rememberSpeaker(); speak("Hello")`. */
@Composable
fun rememberSpeaker(locale: Locale = Locale.getDefault()): (String) -> Unit {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    // Text asked for while the engine starts (a screen that speaks on open) is spoken once it's ready, not dropped.
    val pending = remember { java.util.concurrent.atomic.AtomicReference<String?>(null) }
    lateinit var engine: TextToSpeech
    val tts = remember {
        TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) pending.getAndSet(null)?.let { engine.language = locale; engine.speak(it, TextToSpeech.QUEUE_FLUSH, null, it.hashCode().toString()) }
        }.also { engine = it }
    }
    DisposableEffect(tts) { onDispose { tts.shutdown() } }
    return { text -> if (ready) { tts.language = locale; tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString()) } else pending.set(text) }
}

/** Speech to text through the system recogniser: `val listen = rememberDictation { text -> }; listen()`. */
@Composable
fun rememberDictation(prompt: String = "Speak now", onText: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onText)
    }
    return {
        runCatching { launcher.launch(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, prompt)) }
    }
}

/**
 * The device's location, once. Needs ACCESS_COARSE_LOCATION or ACCESS_FINE_LOCATION (in kiln.json
 * permissions, granted with [rememberPermission]); returns null when location is off or not allowed.
 *
 * A fix newer than [maxAgeMillis] is returned at once; otherwise it asks fused, network, then GPS
 * for a fresh one (each up to [timeoutMillis]), and falls back to the newest known fix — check its
 * `time` if staleness matters.
 */
object KLocation {
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context, maxAgeMillis: Long = 2 * 60_000, timeoutMillis: Long = 15_000): Location? {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (!granted) return null
        val lm = context.getSystemService(LocationManager::class.java)
        if (!lm.isLocationEnabled) return null
        val enabled = lm.allProviders.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val newest = enabled.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.elapsedRealtimeNanos }
        val ageMs = newest?.let { (android.os.SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 }
        if (newest != null && ageMs != null && ageMs <= maxAgeMillis) return newest
        for (p in listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { it in enabled }) {
            val fresh = kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
                suspendCancellableCoroutine<Location?> { c ->
                    val cancel = android.os.CancellationSignal()
                    c.invokeOnCancellation { cancel.cancel() }
                    lm.getCurrentLocation(p, cancel, context.mainExecutor) { loc -> if (c.isActive) c.resume(loc) }
                }
            }
            if (fresh != null) return fresh
        }
        return newest
    }
}

// ---------------------------------------------------------------- background

/**
 * A notification at a later time, even if the app is closed (WorkManager): reminders, timers ending.
 * `KReminder.schedule(ctx, "water", Duration.ofHours(2), "Drink water", "Time for a glass")`; `KReminder.cancel(ctx, "water")`.
 * Needs POST_NOTIFICATIONS (ask with [rememberPermission]).
 */
object KReminder {
    fun schedule(context: Context, id: String, after: Duration, title: String, text: String) {
        val work = OneTimeWorkRequestBuilder<KReminderWorker>().setInitialDelay(after.toMillis(), TimeUnit.MILLISECONDS)
            // Its own notification id per reminder: by title, reminders sharing one ("Task due") replaced each other.
            .setInputData(workDataOf("title" to title, "text" to text, "nid" to id.hashCode())).build()
        WorkManager.getInstance(context).enqueueUniqueWork("kreminder-$id", androidx.work.ExistingWorkPolicy.REPLACE, work)
    }
    /** At a clock time today (or tomorrow if it has passed). */
    fun scheduleAt(context: Context, id: String, hour: Int, minute: Int, title: String, text: String) {
        val now = java.time.ZonedDateTime.now()   // zoned: a DST change between now and then shifted it by an hour
        var at = now.withHour(hour).withMinute(minute).withSecond(0)
        if (!at.isAfter(now)) at = at.plusDays(1)
        schedule(context, id, Duration.between(now, at), title, text)
    }
    fun cancel(context: Context, id: String) { WorkManager.getInstance(context).cancelUniqueWork("kreminder-$id") }
}

/** Posts a [KReminder] (internal; WorkManager creates it by name). */
class KReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val title = inputData.getString("title") ?: "Reminder"
        KNotify.post(applicationContext, title, inputData.getString("text") ?: "", id = inputData.getInt("nid", title.hashCode()))
        return Result.success()
    }
}

// ---------------------------------------------------------------- lifecycle & time

/** Run [action] every time the screen comes back to the foreground (refresh data, recheck a permission). */
@Composable
fun KOnResume(action: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(action)
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) latest() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

/** Run [action] when the app goes to the background (save a draft). */
@Composable
fun KOnPause(action: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(action)
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) latest() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

/** The current time, ticking every [everyMillis] (clocks, "updated 2 min ago"). */
@Composable
fun rememberNow(everyMillis: Long = 1000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(everyMillis) { while (true) { delay(everyMillis); now = System.currentTimeMillis() } }
    return now
}

/** Formatting for people: money, numbers, dates, relative times, durations. */
object KFormat {
    /**
     * Money for display. [currency] is an ISO code ("USD", "INR") or a symbol ("$", "₹", "€") — a
     * symbol is simply put in front of the amount, so a settings field holding either never crashes.
     */
    fun money(amount: Double, currency: String = runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrDefault("USD"), locale: Locale = Locale.getDefault()): String {
        val code = currency.trim()
        if (Regex("^[A-Za-z]{3}$").matches(code)) runCatching {
            return NumberFormat.getCurrencyInstance(locale).apply { this.currency = Currency.getInstance(code.uppercase()) }.format(amount)
        }
        return code + NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(amount)
    }
    fun number(value: Double, decimals: Int = 0): String = NumberFormat.getNumberInstance().apply {
        maximumFractionDigits = decimals; minimumFractionDigits = decimals }.format(value)
    /** 1.2K, 3.4M. */
    fun compact(value: Long): String {
        // Unit picked after rounding (999 950 is "1.0M", not "1000.0K"); negatives keep their sign.
        val a = kotlin.math.abs(value.toDouble()); val sign = if (value < 0) "-" else ""
        if (a < 1000) return value.toString()
        val units = listOf(1e3 to "K", 1e6 to "M", 1e9 to "B")
        var i = 0
        var r = Math.round(a / units[0].first * 10) / 10.0
        while (r >= 1000 && i < units.lastIndex) { i++; r = Math.round(a / units[i].first * 10) / 10.0 }
        return sign + "%.1f".format(r) + units[i].second
    }
    fun percent(fraction: Double, decimals: Int = 0): String = NumberFormat.getPercentInstance().apply { maximumFractionDigits = decimals }.format(fraction)
    fun date(date: LocalDate, pattern: String = "d MMM yyyy"): String = date.format(DateTimeFormatter.ofPattern(pattern))
    fun date(date: LocalDate, formatter: DateTimeFormatter): String = date.format(formatter)
    /** An ISO date string ("2026-10-08", or the date part of "2026-10-08T09:30"); shown as-is if it isn't one. */
    fun date(iso: String, pattern: String = "d MMM yyyy"): String =
        // A timestamp with an offset is shown as its local date ("…T21:30+00:00" is the next day in India).
        // (Midnight UTC is an all-day date, kept as written — as KDate reads it.)
        runCatching { date(java.time.OffsetDateTime.parse(iso).takeIf { it.toLocalTime() != java.time.LocalTime.MIDNIGHT }!!
            .atZoneSameInstant(ZoneId.systemDefault()).toLocalDate(), pattern) }
            .recoverCatching { date(LocalDate.parse(iso.take(10)), pattern) }.getOrDefault(iso)
    fun dateTime(millis: Long, pattern: String = "d MMM, HH:mm"): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern))
    /** "just now", "5 min ago", "yesterday", "3 days ago", or a date. */
    fun relative(millis: Long, now: Long = System.currentTimeMillis()): String {
        val s = (now - millis) / 1000
        // The future (a due date, a reminder) was "just now".
        if (s <= -60) { val f = -s; return when {
            f < 3600 -> "in ${f / 60} min"; f < 86_400 -> "in ${f / 3600} h"; f < 172_800 -> "tomorrow"
            f < 604_800 -> "in ${f / 86_400} days"; else -> dateTime(millis, "d MMM yyyy") } }
        return when {
            s < 60 -> "just now"; s < 3600 -> "${s / 60} min ago"; s < 86_400 -> "${s / 3600} h ago"
            s < 172_800 -> "yesterday"; s < 604_800 -> "${s / 86_400} days ago"; else -> dateTime(millis, "d MMM yyyy") }
    }
    /** 1:05:09 or 05:09. */
    fun duration(seconds: Long): String {
        val h = seconds / 3600; val m = seconds % 3600 / 60; val sec = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }
}
