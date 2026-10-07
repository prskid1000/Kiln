# notifications — post a notification now, or later from background work

- Android 13+ needs POST_NOTIFICATIONS: add it with `set_app_meta` and ask with `rememberPermission`.
- `KNotify.post(context, title, text)` posts on the kit's default channel.
- For a notification at a later time (a reminder, a timer finishing) schedule WorkManager work that
  calls `KNotify.post` — see the `background-work` skill. Don't keep a coroutine alive for it.

```kotlin
import android.Manifest
import android.os.Build
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.kiln.kit.KNotify
import app.kiln.kit.rememberPermission

@Composable
fun SkillNotifyButton() {
    val context = LocalContext.current
    val perm = rememberPermission(Manifest.permission.POST_NOTIFICATIONS)
    Button(onClick = {
        if (Build.VERSION.SDK_INT >= 33 && !perm.granted) perm.request()
        else KNotify.post(context, "Time to drink water", "You're at 1.5 l of 2 l today")
    }) { Text("Remind me") }
}
```
