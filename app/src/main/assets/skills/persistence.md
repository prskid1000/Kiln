# persistence — keep data across restarts (lists, settings, counters, daily totals)

- Lists that grow (expenses, notes, logs): `KCollection<T>("name")` — a small database table with
  `add`, `update(id, …)`, `delete(id)`, `items` (plain values) and `rows` (live `KRow<T>`: `row.id`, `row.value`).
- One value (settings, a goal, a small list): `KStore("name", default)` — `state: StateFlow<T>`, `update { }`, `set()`.
- Create stores once, in an `object Repo` — no Context needed. Types must be `@Serializable`.
- Dates in stored data: `KDate` (also `KTime`, `KDateTime`) — a real `LocalDate`, saved as ISO text.
  Don't store dates as String; every screen then has to parse them.
- New fields need a default value, so data saved by an older version still loads.
- Daily totals: store the date with the value and reset when it isn't today (don't schedule a reset).
- A list screen with add / edit / delete / search: use `KCrudList` (see architecture) instead of building it.

```kotlin
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kiln.kit.KCollection
import app.kiln.kit.KDate
import app.kiln.kit.KFormat
import app.kiln.kit.KListRow
import app.kiln.kit.KStore
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable data class SkillTodo(val text: String, val done: Boolean = false, val due: KDate = LocalDate.now())
@Serializable data class SkillDaily(val date: KDate = LocalDate.now(), val total: Int = 0)
@Serializable data class SkillSettings(val dailyGoal: Int = 8, val name: String = "")

object SkillRepo {
    val todos = KCollection<SkillTodo>("todos")
    val daily = KStore("daily", SkillDaily())
    val settings = KStore("settings", SkillSettings())
}

@Composable
fun SkillTodoList() {
    val rows by SkillRepo.todos.rows.collectAsStateWithLifecycle()
    LazyColumn {
        items(rows, key = { it.id }) { row ->
            KListRow(title = row.value.text, subtitle = "Due " + KFormat.date(row.value.due),
                onClick = { SkillRepo.todos.update(row.id, row.value.copy(done = !row.value.done)) })
        }
    }
}

fun skillAddToday(amount: Int) {
    val today = LocalDate.now()
    SkillRepo.daily.update { d -> if (d.date == today) d.copy(total = d.total + amount) else SkillDaily(today, amount) }
}
```
