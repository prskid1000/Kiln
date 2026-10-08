# networking — call a web API, parse JSON, and show loading / error / data states

- INTERNET is already declared by the kit. Use `https` URLs (cleartext is blocked).
- `KHttp.get<T>(url)` fetches and parses into an `@Serializable` type (unknown keys ignored); `KHttp.post<B, T>(url, body)`
  sends JSON. Errors throw `KHttpException` (with `.code`): catch and show `KError(…) { retry }`.
- Model the screen as Loading / Error / Data and offer Retry. Load in `LaunchedEffect` or a ViewModel.

```kotlin
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.kiln.kit.KError
import app.kiln.kit.KLoading
import app.kiln.kit.KHttp
import kotlinx.serialization.Serializable

@Serializable data class SkillWeather(val current: SkillCurrent)
@Serializable data class SkillCurrent(val temperature_2m: Double)

@Composable
fun SkillWeatherCard() {
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<Result<SkillWeather>?>(null) }
    LaunchedEffect(attempt) {
        state = null
        state = runCatching {
            KHttp.get<SkillWeather>("https://api.open-meteo.com/v1/forecast?latitude=52.52&longitude=13.41&current=temperature_2m")
        }
    }
    val s = state
    when {
        s == null -> KLoading()
        s.isFailure -> KError("Couldn't load the weather", onRetry = { attempt++ })
        else -> Text("Berlin: ${s.getOrThrow().current.temperature_2m} °C")
    }
}
```
