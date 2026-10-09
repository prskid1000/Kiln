# speech — speech to text (dictation, voice commands) and text to speech (read aloud)

- Text to speech: `val speak = rememberSpeaker(); speak("Hello")`. Built into Android: no permission, works offline
  when the voice is installed. Pass a `Locale` for other languages.
- Speech to text, in-app with live words: `val mic = rememberSpeechToText()`; `mic.start()` / `mic.stop()`;
  read `mic.text`, `mic.listening`, `mic.error`. Needs `android.permission.RECORD_AUDIO` in kiln.json permissions,
  granted with `rememberPermission` before `start()`. Check `mic.available` (some devices have no recogniser).
- Speech to text with the system dialog (no permission needed): `val listen = rememberDictation { text -> }; listen()`.
- Voice commands: match on `mic.text.lowercase()` once `mic.listening` turns false.

```kotlin
import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kiln.kit.*

@Composable
fun VoiceNote() {
    val mic = rememberSpeechToText()
    val audio = rememberPermission(Manifest.permission.RECORD_AUDIO)
    val speak = rememberSpeaker()
    var note by remember { mutableStateOf("") }
    val dictate = rememberDictation { note = it }
    // Keep the words when listening ends: by itself after a pause, or after Stop (the final result comes then).
    LaunchedEffect(mic.listening) { if (!mic.listening && mic.text.isNotEmpty()) note = mic.text }

    KilnScreen("Voice note") { padding ->
        Column(Modifier.screenPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            KCardBox(height = 140.dp) {
                Text(if (mic.listening) mic.text.ifEmpty { "Listening…" } else note.ifEmpty { "Tap Speak and talk" },
                    style = MaterialTheme.typography.bodyLarge)
            }
            mic.error?.let { KAlert(it, tone = KTone.Danger) }
            KButton(if (mic.listening) "Stop" else "Speak", icon = if (mic.listening) Icons.Filled.Stop else Icons.Filled.Mic, fullWidth = true) {
                when {
                    !audio.granted -> audio.request()
                    mic.listening -> mic.stop()
                    else -> mic.start()
                }
            }
            KButton("Use the system dialog", variant = KVariant.Outline, fullWidth = true) { dictate() }
            KButton("Read it back", variant = KVariant.Tonal, icon = Icons.Filled.VolumeUp, fullWidth = true, enabled = note.isNotBlank()) { speak(note) }
        }
    }
}
```
