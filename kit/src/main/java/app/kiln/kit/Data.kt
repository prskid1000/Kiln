package app.kiln.kit

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File

/** The JSON settings every Kiln app uses (lenient reads, defaults kept). */
val KJson: Json = Json {
    ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false; isLenient = true
    // A null where the type has a default (a nullable server column) takes the default instead of failing the read.
    coerceInputValues = true
    // An average over zero items is NaN: saving it must not crash the app.
    allowSpecialFloatingPointValues = true
    // Dates on their own (KStore("last", LocalDate.now()), KCollection<KDate>) — the typealias annotation only
    // reaches properties inside a @Serializable class.
    serializersModule = kotlinx.serialization.modules.SerializersModule {
        contextual(java.time.LocalDate::class, KDateSerializer)
        contextual(java.time.LocalTime::class, KTimeSerializer)
        contextual(java.time.LocalDateTime::class, KDateTimeSerializer)
    }
}

/**
 * A typed, persisted value: one JSON file in the app's files dir, exposed as a
 * StateFlow. Writes are atomic (temp file + rename) and serialized.
 *
 * ```
 * @Serializable data class Todo(val id: Long, val text: String, val done: Boolean = false)
 * val todos = KStore("todos", emptyList<Todo>())   // no Context needed (an overload takes one)
 * val list by todos.state.collectAsStateWithLifecycle()
 * todos.update { it + Todo(System.currentTimeMillis(), "Buy milk") }
 * ```
 */
// Constructed only through the shared registry (KStore(context, name, default)): two copies of one file overwrite each other.
class KStore<T> @PublishedApi internal constructor(context: Context, name: String, private val default: T, private val serializer: KSerializer<T>) {
    private val file = File(context.applicationContext.filesDir, "kstore/$name.json")
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flow = MutableStateFlow(load())
    val state: StateFlow<T> = flow.asStateFlow()
    val value: T get() = flow.value

    private fun load(): T {
        if (!file.exists()) return default
        return runCatching { KJson.decodeFromString(serializer, file.readText()) }.getOrElse { e ->
            // Saved data that no longer decodes (the type changed) must not be overwritten by the next save:
            // keep it beside the store so it can be recovered.
            runCatching { file.copyTo(File(file.path + ".unreadable-" + System.currentTimeMillis()), overwrite = false) }
            android.util.Log.w("KStore", "${file.name} couldn't be read (${e.message}); kept a copy and started from the default")
            default
        }
    }

    /** Apply [change] to the current value and persist it. */
    fun update(change: (T) -> T) {
        // Atomic against other threads' updates; each save writes the latest value, so saves can't land out of order.
        flow.update(change)
        scope.launch { lock.withLock { write(flow.value) } }
    }

    fun set(value: T): Unit = update { value }

    // A save that fails (full disk, a value that can't be encoded) is logged; it ran in the background and
    // would otherwise kill the app with no app frame in the trace. The value stays in memory.
    private fun write(value: T) = runCatching {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(KJson.encodeToString(serializer, value))
        tmp.renameTo(file)
    }.onFailure { android.util.Log.w("KStore", "${file.name} couldn't be saved: ${it.message}") }

    companion object {
        /** One store per name: two screens reading "settings" must see the same value, not two copies. */
        @PublishedApi internal val open = java.util.concurrent.ConcurrentHashMap<String, KStore<*>>()

        @Suppress("UNCHECKED_CAST")
        @PublishedApi internal fun <T> shared(context: Context, name: String, default: T, serializer: KSerializer<T>): KStore<T> =
            open.getOrPut(name) { KStore(context, name, default, serializer) } as KStore<T>

        inline operator fun <reified T> invoke(context: Context, name: String, default: T): KStore<T> =
            shared(context, name, default, KJson.serializersModule.serializer())
        /** No Context needed: `val settings = KStore("settings", Settings())` (uses [KApp.context]). */
        inline operator fun <reified T> invoke(name: String, default: T): KStore<T> =
            shared(KApp.context, name, default, KJson.serializersModule.serializer())
    }
}
