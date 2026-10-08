package app.kiln.kit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable

/**
 * Base activity for every Kiln app: edge-to-edge, the theme, crash hook.
 *
 * ```
 * class MainActivity : KilnActivity() {
 *     @Composable override fun Content() { KilnScreen(title = "Hello") { … } }
 * }
 * ```
 * The theme is Nocturne unless the app overrides [Theme]:
 * ```
 * @Composable override fun Theme(content: @Composable () -> Unit) = KilnTheme(theme = KThemes.OceanDepths, content = content)
 * ```
 */
abstract class KilnActivity : ComponentActivity() {
    @Composable abstract fun Content()

    /** The app's look; Nocturne by default. Override to use a [KThemes] preset, a brand colour, density… */
    @Composable open fun Theme(content: @Composable () -> Unit) = KilnTheme(content = content)

    override fun onCreate(savedInstanceState: Bundle?) {
        KilnCrash.install(this)
        KilnInspector.install(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { Theme { Content() } }
    }
}
