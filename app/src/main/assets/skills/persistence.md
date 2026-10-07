# persistence — keep data across restarts (lists, settings, counters, daily totals)

- Collections and app data: `KStore(context, "name", default)` — a JSON file with `state: StateFlow<T>`,
  `update { }` and `set()`. Types must be `@Serializable`. Create it once (remember / ViewModel / object).
- Small settings (a toggle, a goal number): the same KStore with a settings data class is simplest.
- Daily totals: store the date with the value and reset when it isn't today (don't schedule a reset).

```kotlin
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kiln.kit.KListRow
import app.kiln.kit.KStore
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable data class SkillTodo(val id: Long, val text: String, val done: Boolean = false)
@Serializable data class SkillDaily(val date: String = "", val total: Int = 0)

@Composable
fun SkillTodoList() {
    val context = LocalContext.current
    val store = remember { KStore(context, "todos", emptyList<SkillTodo>()) }
    val todos by store.state.collectAsStateWithLifecycle()
    LazyColumn {
        items(todos, key = { it.id }) { t ->
            KListRow(title = t.text, onClick = { store.update { list -> list.map { if (it.id == t.id) it.copy(done = !it.done) else it } } })
        }
    }
}

fun skillAddToday(store: KStore<SkillDaily>, amount: Int) {
    val today = LocalDate.now().toString()
    store.update { d -> if (d.date == today) d.copy(total = d.total + amount) else SkillDaily(today, amount) }
}
```
