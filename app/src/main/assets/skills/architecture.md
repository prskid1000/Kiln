# architecture — how to structure an app: state, data, screens, files

Load this before starting any app with more than one screen or any saved data.

**State and data flow**
- One source of truth per piece of data: a `KStore` (one value), `KCollection` (a growing list) or a ViewModel's
  `StateFlow`. Screens read it with `collectAsStateWithLifecycle()` and never keep their own copy.
- Data flows down, events flow up: a screen composable takes plain values and lambdas (`items`, `onAdd`, `onDelete`).
  Only the top-level screen touches the store or ViewModel. This keeps screens previewable and testable.
- `remember { mutableStateOf(…) }` is for transient UI state (a dialog open, a text being typed).
  `rememberSaveable` survives rotation. Anything the user would be upset to lose goes in `KStore` / `KCollection`.
- Long work (network, files) runs in `viewModelScope` or `rememberCoroutineScope()`, never on the main thread.
  Model it as loading / content / error and show `KSkeleton`/`KLoading`, the content, or `KError(…) { retry }`.

**Files** (package = the app's package)
- `MainActivity.kt` — the entry point and navigation only.
- `data/` — @Serializable models and the stores as plain values: `object Repo { val notes = KCollection<Note>("notes"); val settings = KStore("settings", Settings()) }`. No Context, no lateinit, no setup call (the kit knows the app's context from process start), so it works from screens, workers and receivers alike.
- `ui/<Feature>Screen.kt` — one file per screen: the stateful `XScreen()` plus a stateless `XContent(state, events)`.
- `ui/components/` — composables reused by two or more screens.
- Keep files under ~300 lines; split by feature, not by layer, once a feature grows.

**Lists of saved things** (expenses, notes, plants, tasks): use `KCrudList(items = Repo.x, newItem = { X() }, title = { … }) { draft, set -> form fields }`. It already does the list, search, swipe-to-delete with Undo, empty state, + button and the add/edit sheet with validation; open it from elsewhere with `rememberKCrudState()` → `crud.add()`. Don't hand-build these.

**Navigation**: up to 5 top-level destinations → `KilnTabs`; drill-down (list → detail) → Navigation 3
(see the kit reference). Pass ids between screens, not whole objects.

**Errors**: never let an exception reach the user as a crash. Catch at the boundary (network, file, parse)
and turn it into state the UI shows.

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kiln.kit.*
import kotlinx.serialization.Serializable

// data/Note.kt — models first, then the stores, then screens, then MainActivity
@Serializable data class Note(val title: String, val body: String = "")

// data/Repo.kt
object Repo {
    val notes = KCollection<Note>("notes")
}

// ui/NotesScreen.kt — stateful: owns the store, turns events into writes
@Composable
fun NotesScreen() {
    val rows by Repo.notes.rows.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    KilnScreen("Notes", floatingAction = { KFab(Icons.Filled.Add, "Add note") { adding = true } }) { padding ->
        NotesContent(rows, onDelete = { Repo.notes.delete(it) }, modifier = Modifier.screenPadding(padding))
    }
    AddNoteDialog(adding, onDismiss = { adding = false }, onSave = { Repo.notes.add(Note(it)) })
}

// stateless: values in, events out
@Composable
fun NotesContent(rows: List<KRow<Note>>, onDelete: (Long) -> Unit, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) { KEmptyState("No notes yet", "Tap + to write your first note.", modifier = modifier); return }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(rows, key = { it.id }) { row ->
            KSwipeRow(onDelete = { onDelete(row.id); true }) {
                KListRow(row.value.title, subtitle = KFormat.relative(row.updatedAt))
            }
        }
    }
}

@Composable
fun AddNoteDialog(open: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var title by remember { mutableStateOf("") }
    KDialog(open, "New note", onDismiss, confirmLabel = "Save", onConfirm = { if (title.isNotBlank()) onSave(title.trim()); title = "" }) {
        Column { KTextField(title, { title = it }, label = "Title", maxLength = 80) }
    }
}
```
