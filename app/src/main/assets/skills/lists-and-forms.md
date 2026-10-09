# lists-and-forms — add/edit/delete items with a form, swipe to delete, and an empty state

- **Saved items (expenses, notes, tasks): `KCrudList`.** It already has the list, search, swipe-to-delete with Undo,
  the empty state, the + button and the add/edit sheet with validation. Build it by hand only for what it can't do.
- **An add/edit form opens as its own surface** — `KBottomSheet`, `KDialog`, or a pushed `KilnScreen` — never as a
  `Box(Modifier.fillMaxSize())` over the current screen: that has no background, so the list shows through the form
  and the text of both is drawn over each other.
- **Show numbers the way people write them:** an amount in a field is `"750"`, not `750.0.toString()` ("750.0");
  shown elsewhere it's `KFormat.money(amount, currency)`.
- **A currency is chosen, not typed:** `KSelect(listOf("INR", "USD", "EUR", …))` — a free text field let "₹1000" in,
  and every amount then read "₹1000750.00".

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
