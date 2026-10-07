@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package app.kiln.kit

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Nocturne — the one theme every Kiln app uses (shared with Warden, Sundown,
 * Vessel and On Device AI). Apps never define colours or fonts of their own;
 * they read [Nocturne] tokens or MaterialTheme, which [KilnTheme] fills.
 */
object Nocturne {
    val bg = Color(0xFF161826)
    val surface = Color(0xFF232532)
    val surfaceHi = Color(0xFF2A2C3A)
    val text = Color(0xFFE9E9ED)
    val accent = Color(0xFF9184D9)
    val accent2 = Color(0xFFA7A1DB)
    val accent100 = Color(0xFFF5F4FF)
    val accent300 = Color(0xFFD2CEFD)
    val accent800 = Color(0xFF423A6A)
    val accent900 = Color(0xFF2B2741)
    val neutral100 = Color(0xFFF3F5FE)
    val neutral300 = Color(0xFFCFD3E5)
    val neutral600 = Color(0xFF75798C)
    val neutral700 = Color(0xFF595D6C)
    val neutral800 = Color(0xFF3F424D)
    val ok = Color(0xFF7FB69A)
    val warn = Color(0xFFD9C48A)
    val danger = Color(0xFFD98A8A)
    val textMuted = text.copy(alpha = 0.55f)
    val textLabel = text.copy(alpha = 0.70f)
    val divider = text.copy(alpha = 0.16f)

    val sans = FontFamily(
        Font(R.font.inter_variable, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.inter_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.inter_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    )
    val mono = FontFamily(
        Font(R.font.jetbrains_mono_variable, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.jetbrains_mono_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    )
}

private val scheme = darkColorScheme(
    primary = Nocturne.accent, onPrimary = Nocturne.bg,
    primaryContainer = Nocturne.accent800, onPrimaryContainer = Nocturne.accent100,
    secondary = Nocturne.accent2, onSecondary = Nocturne.bg,
    secondaryContainer = Nocturne.accent900, onSecondaryContainer = Nocturne.accent300,
    tertiary = Nocturne.ok, onTertiary = Nocturne.bg,
    background = Nocturne.bg, onBackground = Nocturne.text,
    surface = Nocturne.bg, onSurface = Nocturne.text,
    surfaceVariant = Nocturne.surface, onSurfaceVariant = Nocturne.textLabel,
    surfaceContainerLowest = Nocturne.bg, surfaceContainerLow = Nocturne.surface,
    surfaceContainer = Nocturne.surface, surfaceContainerHigh = Nocturne.surfaceHi,
    surfaceContainerHighest = Nocturne.surfaceHi,
    outline = Nocturne.neutral700, outlineVariant = Nocturne.neutral800,
    error = Nocturne.danger, onError = Nocturne.bg,
)

private fun s(size: Int, line: Int, w: FontWeight = FontWeight.Normal, track: Double = 0.0, mono: Boolean = false) =
    TextStyle(fontFamily = if (mono) Nocturne.mono else Nocturne.sans, fontWeight = w,
        fontSize = size.sp, lineHeight = line.sp, letterSpacing = track.em)

private val type = Typography(
    displayLarge = s(44, 50, FontWeight.Medium, -0.02), displayMedium = s(36, 42, FontWeight.Medium, -0.02),
    displaySmall = s(30, 36, FontWeight.Medium, -0.015),
    headlineLarge = s(28, 34, FontWeight.Medium, -0.015), headlineMedium = s(25, 28, FontWeight.Medium, -0.015),
    headlineSmall = s(21, 25, FontWeight.Medium, -0.015),
    titleLarge = s(20, 24, FontWeight.Medium, -0.01), titleMedium = s(17, 21, FontWeight.Medium, -0.01),
    titleSmall = s(15, 19, FontWeight.Medium),
    bodyLarge = s(16, 24), bodyMedium = s(14, 21), bodySmall = s(13, 18),
    labelLarge = s(14, 17, FontWeight.Medium), labelMedium = s(12, 15, FontWeight.Medium),
    labelSmall = s(11, 14, FontWeight.Medium, 0.04),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(14.dp), extraLarge = RoundedCornerShape(20.dp),
)

/** Wrap every screen in this. There is no light theme: Nocturne is dark by design. */
@Composable
fun KilnTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes, content = content)
