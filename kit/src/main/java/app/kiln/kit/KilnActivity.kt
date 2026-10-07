package app.kiln.kit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable

/**
 * Base activity for every Kiln app: edge-to-edge, Nocturne theme, crash hook.
 *
 * ```
 * class MainActivity : KilnActivity() {
 *     @Composable override fun Content() { KilnScreen(title = "Hello") { … } }
 * }
 * ```
 */
abstract class KilnActivity : ComponentActivity() {
    @Composable abstract fun Content()

    override fun onCreate(savedInstanceState: Bundle?) {
        KilnCrash.install(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { KilnTheme { Content() } }
    }
}
