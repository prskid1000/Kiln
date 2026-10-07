package app.kiln.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kiln.Graph
import app.kiln.build.AppSecrets
import app.kiln.ui.theme.N
import app.kiln.ui.theme.T
import app.kiln.ui.theme.vCard

fun KilnVM.secretNames(name: String): List<String> = AppSecrets.names(state(name).project)

fun KilnVM.hasSecret(name: String, key: String): Boolean = Graph.secrets.has(AppSecrets.storeId(state(name).project, key))

fun KilnVM.setSecret(name: String, key: String, value: String): String? {
    val p = state(name).project
    val k = key.trim().uppercase().replace(Regex("[^A-Z0-9_]"), "_")
    if (!AppSecrets.NAME.matches(k)) return "Names start with a letter: A–Z, 0–9 and _"
    Graph.secrets.put(AppSecrets.storeId(p, k), value.ifEmpty { null })
    AppSecrets.setNames(p, AppSecrets.names(p) + k)
    return null
}

fun KilnVM.removeSecret(name: String, key: String) {
    val p = state(name).project
    Graph.secrets.put(AppSecrets.storeId(p, key), null)
    AppSecrets.setNames(p, AppSecrets.names(p) - key)
}

/** Keys the app needs, kept out of its source, chats and repository. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretsSheet(vm: KilnVM, ps: ProjectState, onDismiss: () -> Unit) {
    val name = ps.project.name
    var names by remember { mutableStateOf(vm.secretNames(name)) }
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismiss, containerColor = N.surface) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Secrets", style = T.cardTitle)
            Text("API keys this app needs. The code reads them as AppSecrets.NAME; the values stay in Kiln's encrypted " +
                "store and never appear in the source, chats or GitHub.", style = T.bodySmall)
            Text("They are built into the app, and anything in an APK can be extracted — use only client-side keys, " +
                "restricted to this app where the service allows it. Never a server or admin key.",
                style = T.bodySmall.copy(color = N.warn))
            names.forEach { n ->
                Row(Modifier.fillMaxWidth().vCard(N.shapeMd).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(n, style = T.mono.copy(color = N.text))
                        Text(if (vm.hasSecret(name, n)) "set" else "no value yet", style = T.label)
                    }
                    KButton("Change") { key = n; value = "" }
                    IconBtn(Icons.Rounded.DeleteOutline, "Remove $n") { vm.removeSecret(name, n); names = vm.secretNames(name) }
                }
            }
            // Uppercased on save: rewriting the text while typing makes the keyboard drop characters.
            KField("Name", key, { key = it }, mono = true, hint = "WEATHER_API_KEY")
            KField("Value", value, { value = it }, mono = true, hint = "paste the key", secret = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                KButton("Save", Tone.Accent) {
                    error = vm.setSecret(name, key, value)
                    if (error == null) { names = vm.secretNames(name); key = ""; value = "" }
                }
                Spacer(Modifier.width(12.dp))
                error?.let { Text(it, style = T.bodySmall.copy(color = N.danger)) }
            }
        }
    }
}
