# lists-and-forms — add/edit/delete items with a form, swipe to delete, and an empty state

- `LazyColumn` with stable `key`s; `KEmptyState` when the list is empty.
- Swipe to delete: `SwipeToDismissBox` (Material 3). Always offer a visible delete too (accessibility).
- Forms: validate on submit, disable Save until valid, `KeyboardOptions` from `androidx.compose.foundation.text`.

```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import app.kiln.kit.KEmptyState
import app.kiln.kit.KListRow

@Composable
fun SkillEditableList(items: List<Pair<Long, String>>, onAdd: (String) -> Unit, onDelete: (Long) -> Unit) {
    var draft by remember { mutableStateOf("") }
    Column {
        OutlinedTextField(draft, { draft = it }, label = { Text("New item") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), singleLine = true)
        Button(enabled = draft.isNotBlank(), onClick = { onAdd(draft.trim()); draft = "" }) { Text("Add") }
        if (items.isEmpty()) KEmptyState(title = "Nothing yet", body = "Add your first item above.")
        else LazyColumn {
            items(items, key = { it.first }) { (id, text) ->
                // Deleted once, when the swipe is confirmed (not on every recomposition while it animates away).
                val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                    if (v == SwipeToDismissBoxValue.EndToStart) { onDelete(id); true } else false },
                    // Only right-to-left deletes; a swipe the other way snaps back instead of leaving a gap.
                )
                SwipeToDismissBox(state, backgroundContent = {}) { KListRow(title = text) }
            }
        }
    }
}
```
