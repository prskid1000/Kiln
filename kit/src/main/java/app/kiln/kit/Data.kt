package app.kiln.kit

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File

/** The JSON settings every Kiln app uses (lenient reads, defaults kept). */
val KJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false; isLenient = true }

/**
 * A typed, persisted value: one JSON file in the app's files dir, exposed as a
 * StateFlow. Writes are atomic (temp file + rename) and serialized.
 *
 * ```
 * @Serializable data class Todo(val id: Long, val text: String, val done: Boolean = false)
 * val todos = KStore(context, "todos", emptyList<Todo>())
 * val list by todos.state.collectAsStateWithLifecycle()
 * todos.update { it + Todo(System.currentTimeMillis(), "Buy milk") }
 * ```
 */
class KStore<T>(context: Context, name: String, private val default: T, private val serializer: KSerializer<T>) {
    private val file = File(context.applicationContext.filesDir, "kstore/$name.json")
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flow = MutableStateFlow(load())
    val state: StateFlow<T> = flow.asStateFlow()
    val value: T get() = flow.value

    private fun load(): T = runCatching {
        if (file.exists()) KJson.decodeFromString(serializer, file.readText()) else default
    }.getOrDefault(default)

    /** Apply [change] to the current value and persist it. */
    fun update(change: (T) -> T) {
        val next = change(flow.value)
        flow.value = next
        scope.launch { lock.withLock { write(next) } }
    }

    fun set(value: T) = update { value }

    private fun write(value: T) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(KJson.encodeToString(serializer, value))
        tmp.renameTo(file)
    }

    companion object {
        inline operator fun <reified T> invoke(context: Context, name: String, default: T): KStore<T> =
            KStore(context, name, default, serializer())
        /** No Context needed: `val settings = KStore("settings", Settings())` (uses [KApp.context]). */
        inline operator fun <reified T> invoke(name: String, default: T): KStore<T> =
            KStore(KApp.context, name, default, serializer())
    }
}
