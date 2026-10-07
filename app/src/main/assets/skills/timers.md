# timers — countdowns and stopwatches that survive rotation and don't drift

- Store the *end time* (or start time), not a decreasing counter; compute the remaining time each tick.
- Tick with `LaunchedEffect` + `delay`; keep state in `rememberSaveable` or a store.
- To notify when it ends while the app is closed, schedule WorkManager work for the end time
  (see `background-work`); the on-screen tick is only for display.

```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

@Composable
fun SkillCountdown(seconds: Int) {
    var endAt by rememberSaveable { mutableLongStateOf(0L) }
    var now by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endAt) { while (endAt > now) { delay(200); now = System.currentTimeMillis() } }
    val left = ((endAt - now).coerceAtLeast(0) + 999) / 1000
    Column {
        Text("%d:%02d".format(left / 60, left % 60), style = MaterialTheme.typography.displayLarge)
        Button(onClick = { now = System.currentTimeMillis(); endAt = now + seconds * 1000L }) { Text("Start") }
    }
}
```
