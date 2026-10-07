# supabase — a hosted database and sign-in with KSupabase

- Create a Supabase project; copy its URL and the **anon** key into the app's Secrets
  (ask the user: Secrets → SUPABASE_URL, SUPABASE_ANON_KEY), read them as `AppSecrets.…`.
- Turn on Row Level Security for every table and add policies (e.g. `auth.uid() = user_id`).
- Insert with a type that leaves out server-filled columns (id, created_at), then reload.
- Network calls are suspend functions: call them from `LaunchedEffect` / a coroutine scope and
  show errors (no network, wrong key) on screen.

```kotlin
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.kiln.kit.KSupabase
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data class Note(val id: Long, val text: String)
@Serializable data class NewNote(val text: String)

class NotesRepo(context: Context, url: String, anonKey: String) {
    // In a real app: KSupabase(context, AppSecrets.SUPABASE_URL, AppSecrets.SUPABASE_ANON_KEY)
    private val db = KSupabase(context, url, anonKey)
    suspend fun all(): List<Note> = db.table("notes").select<Note>("select=id,text&order=id.desc")
    suspend fun add(text: String) { db.table("notes").insert(NewNote(text)) }
    suspend fun remove(id: Long) = db.table("notes").delete("id=eq.$id")
}

@Composable
fun NotesFromSupabase(url: String, anonKey: String) {
    val context = LocalContext.current
    val repo = remember { NotesRepo(context, url, anonKey) }
    val scope = rememberCoroutineScope()
    var notes by remember { mutableStateOf<List<Note>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    suspend fun reload() { runCatching { repo.all() }.onSuccess { notes = it; error = null }.onFailure { error = it.message } }
    LaunchedEffect(Unit) { reload() }
    Column {
        error?.let { Text("Couldn't load: $it") }
        notes.forEach { n -> Text(n.text) }
        Button(onClick = { scope.launch { runCatching { repo.add("Hello") }; reload() } }) { Text("Add") }
    }
}
```
