# permissions — ask for a runtime permission (camera, location, mic, notifications) and react to the answer

1. Declare it: `set_app_meta` with the full permission list, e.g. `["CAMERA"]` (short names are fine).
2. In the UI use the kit's `rememberPermission(...)`: `.granted` is state, `.request()` shows the system dialog.
3. Explain why before asking, and offer a way forward when denied (the user can grant it in Settings).
4. Never call the guarded API before `granted` is true.

```kotlin
import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import app.kiln.kit.rememberPermission

@Composable
fun SkillCameraGate(content: @Composable () -> Unit) {
    val camera = rememberPermission(Manifest.permission.CAMERA)
    if (camera.granted) content()
    else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("The camera is used to scan receipts. Nothing leaves the phone.")
        Button(onClick = { camera.request() }) { Text("Allow camera") }
    }
}
```
