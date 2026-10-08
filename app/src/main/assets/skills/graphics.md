# graphics — icons, illustrations, logos and the launcher icon with make_graphic

- Draw with `make_graphic`: write SVG, pick the output by path, look at the preview it returns, and iterate.
  - `res/drawable/<name>.xml` → vector drawable (best for icons, logos, illustrations: crisp, tiny). Use with
    `painterResource(R.drawable.<name>)` in `Image`/`Icon`.
  - `res/drawable-nodpi/<name>.png` → a bitmap at `size` px (complex art, textures, gradients with filters).
  - `assets/<name>.svg` → shown with `KSvg("file:///android_asset/<name>.svg", "…")`.
- Before drawing your own icon, check the Material icon set: `Icons.Filled.*` / `Outlined` / `Rounded` has 2,000+.
- Launcher icon: `res/drawable/ic_launcher.xml`, viewBox `0 0 108 108`. Keep the motif inside the central 66×66
  (21..87): the outer ring gets cropped to a circle or squircle. Fill the full 108 square with the background.
  One bold, simple shape that reads at 48 px; no text, no thin lines.
- Style: flat shapes, 2–3 colours from the app's theme (Nocturne: accent `#9184D9`, accent2 `#A7A1DB`, ok `#7FB69A`,
  warn `#D9C48A`, bg `#161826`; a preset's palette is in `KThemes`), rounded joins, consistent stroke width (2 in a 24 grid).
- Illustrations for empty states: 160–200 dp wide, muted (accent at 30–60 % opacity), placed above `KEmptyState`'s text.
- Vector drawables can't do filters, masks, text or patterns. The tool says what it left out; draw those shapes as paths
  or make a PNG instead.

```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.dp
import app.kiln.kit.*

// An SVG from assets (written by make_graphic to assets/hero.svg)
@Composable
fun Hero() = KSvg("file:///android_asset/hero.svg", "Plant illustration", Modifier.size(180.dp))

// A Material icon first: often there is already one that fits
@Composable
fun LeafIcon() = Icon(Icons.Rounded.Eco, "Plants", tint = Nocturne.ok)

// A small vector built in code, when it must change at runtime (e.g. a progress badge)
val Badge: ImageVector = ImageVector.Builder("badge", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color(0xFF8B7CF6))) { moveTo(12f, 2f); lineTo(22f, 12f); lineTo(12f, 22f); lineTo(2f, 12f); close() }
}.build()

@Composable
fun BadgeImage() = Column { Image(rememberVectorPainter(Badge), "Badge", Modifier.size(32.dp)) }
```
