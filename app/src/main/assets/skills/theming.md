# theming — change the app's look: presets, brand colour, light/dark, font, corners, size, transparency

- Default is Nocturne. Only change the theme when the user asks for a look, colour, mood or brand.
- Change it in ONE place: override `Theme` in MainActivity. Every component and every `Nocturne.*` read follows.
  Never sprinkle `Color(0x…)` through screens.
- Map the request to a preset first:
  calm/professional → `OceanDepths` · cosy/warm → `GoldenHour` or `SunsetBoulevard` · natural/eco → `ForestCanopy`
  or `BotanicalGarden` · clean/minimal → `ModernMinimalist` or `ArcticFrost` · elegant/soft → `DesertRose` ·
  bold/techy/gaming → `TechInnovation` · dreamy/night → `MidnightGalaxy`.
- A specific colour ("make it pink", a brand hex) → `KilnTheme(accent = Color(0xFFE91E63))`; add `dark = false` for a light app.
- "Bigger / easier to tap / for older users" → `density = KDensity.Comfortable` or `Large`, `textScale = 1.15f`.
  "Denser / fit more" → `KDensity.Compact`. "Rounder / sharper" → `corners = KCorners.Round / Sharp`.
- A surface style: "glassy / glassmorphism / frosted" → `style = KStyle.Glass`; "soft UI / neumorphism" →
  `KStyle.Neumorphic` (pair with a light theme); "bold / brutalist / retro" → `KStyle.Brutalist`; "material / cards
  that float" → `KStyle.Elevated`; "wireframe / outlined" → `KStyle.Outlined`; "clay / claymorphism / playful 3D" →
  `KStyle.Clay`; "neon / cyberpunk / glow" → `KStyle.Neon` (dark theme); "skeuomorphic / realistic / retro gadget" →
  `KStyle.Skeuomorphic`.
- See-through cards over your own photo → `surfaceAlpha = 0.7f` and draw the image behind the content.
- One element different (the user asked for exactly that) → `colors = KColors(container = …, content = …)` on that component.
- Let the app's own users choose → `KThemePicker` + `rememberStored`, as below.
- After changing the theme, redraw the launcher icon in the new palette (make_graphic) and re-check screens with run_app.

```kotlin
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.kiln.kit.*

// 1. A preset for the whole app (in MainActivity.kt)
class PresetActivity : KilnActivity() {
    @Composable override fun Theme(content: @Composable () -> Unit) = KilnTheme(theme = KThemes.OceanDepths, content = content)
    @Composable override fun Content() { Text("Hello") }
}

// 2. A brand colour, light, rounder, roomier, bigger text
class BrandActivity : KilnActivity() {
    @Composable override fun Theme(content: @Composable () -> Unit) = KilnTheme(
        accent = Color(0xFFE91E63), dark = false, font = KFonts.System, corners = KCorners.Round,
        density = KDensity.Comfortable, textScale = 1.1f, content = content)
    @Composable override fun Content() { Text("Hello") }
}

// 3. The user picks the theme inside the app; the choice is remembered
class PickableActivity : KilnActivity() {
    @Composable override fun Theme(content: @Composable () -> Unit) {
        val name by rememberStored("theme", KThemes.Nocturne.name)
        KilnTheme(theme = KThemes.named(name) ?: KThemes.Nocturne, content = content)
    }
    @Composable override fun Content() { AppearanceScreen() }
}

@Composable
fun AppearanceScreen() {
    var name by rememberStored("theme", KThemes.Nocturne.name)
    KilnScreen("Appearance") { padding ->
        Column(Modifier.screenPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            KSection("Theme")
            KThemePicker(name, { name = it.name })
            KButton("Primary action", fullWidth = true) {}
        }
    }
}

// 4. A theme + a surface style
class GlassActivity : KilnActivity() {
    @Composable override fun Theme(content: @Composable () -> Unit) = KilnTheme(theme = KThemes.MidnightGalaxy, style = KStyle.Glass, content = content)
    @Composable override fun Content() { GlassHome() }
}

// 5. Translucent cards over your own gradient (KilnTheme(surfaceAlpha = 0.7f) in Theme())
@Composable
fun GlassHome() {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Nocturne.accent, Nocturne.bg)))) {
        Column(Modifier.screenPadding(androidx.compose.foundation.layout.PaddingValues(top = 48.dp)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            KCardBox(variant = KVariant.Tonal) { Text("Translucent card") }
            // One component styled differently, because the user asked for exactly this one:
            KButton("Order now", colors = KColors(container = Color(0xFF2E7D32), content = Color.White), fullWidth = true) {}
        }
    }
}
```
