package {{package}}

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kiln.kit.KilnActivity
import app.kiln.kit.KilnScreen
import app.kiln.kit.screenPadding

class MainActivity : KilnActivity() {
    @Composable
    override fun Content() {
        KilnScreen(title = "{{label}}") { padding ->
            Column(Modifier.screenPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Built on this phone with Kiln.", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
