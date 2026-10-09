# photos — let the user pick a photo or take a quick picture

- Pick from the gallery with the Photo Picker (`PickVisualMedia`): no permission needed. Show it with
  `AsyncImage(model = uri, …)`. To keep it, copy the bytes into `filesDir` (picker URIs expire).
- A quick camera shot: `rememberCameraShot { bitmap -> }` returns a small Bitmap, no FileProvider needed. No permission
  either — unless the app declares CAMERA, which must then be granted first. `shoot()` is false if the camera couldn't
  open; say so.

```kotlin
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import app.kiln.kit.rememberCameraShot

@Composable
fun SkillPhotoPicker() {
    var picked by remember { mutableStateOf<Uri?>(null) }
    var shot by remember { mutableStateOf<Bitmap?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked = it }
    val take = rememberCameraShot { shot = it }
    var noCamera by remember { mutableStateOf(false) }
    Column {
        Button(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Choose photo") }
        Button(onClick = { noCamera = !take() }) { Text("Take photo") }
        if (noCamera) Text("Couldn't open the camera")
        picked?.let { AsyncImage(model = it, contentDescription = "Chosen photo", modifier = Modifier.size(200.dp)) }
        shot?.let { Image(it.asImageBitmap(), contentDescription = "Photo just taken", modifier = Modifier.size(200.dp)) }
    }
}
```
