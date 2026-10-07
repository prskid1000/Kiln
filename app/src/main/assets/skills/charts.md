# charts — draw a bar or line chart (weekly totals, progress) with Canvas, no library

- `Canvas` with `drawRect` / `drawLine` / `drawCircle`; colours from `MaterialTheme.colorScheme`.
- Scale to the max value; leave room for labels; describe the chart for TalkBack with `semantics`.

```kotlin
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun SkillBarChart(values: List<Float>, labelForTalkBack: String) {
    val bar = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(160.dp).semantics { contentDescription = labelForTalkBack }) {
        if (values.isEmpty()) return@Canvas
        val max = values.max().coerceAtLeast(1f)
        val slot = size.width / values.size
        val w = slot * 0.6f
        values.forEachIndexed { i, v ->
            val x = i * slot + (slot - w) / 2
            drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(8f))
            val h = size.height * (v / max)
            drawRoundRect(bar, Offset(x, size.height - h), Size(w, h), CornerRadius(8f))
        }
    }
}
```
