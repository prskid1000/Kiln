@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package app.kiln.kit

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Every colour token the kit draws with. [KilnTheme] builds one from a [KTheme] or a brand
 * colour, and [Nocturne] exposes the active one.
 */
data class KPalette(
    val dark: Boolean,
    val bg: Color, val surface: Color, val surfaceHi: Color, val text: Color,
    val accent: Color, val accent2: Color,
    /** On-selection text, light accent tint, selection background, deepest accent tint. */
    val accent100: Color, val accent300: Color, val accent800: Color, val accent900: Color,
    val neutral100: Color, val neutral300: Color, val neutral600: Color, val neutral700: Color, val neutral800: Color,
    val ok: Color, val warn: Color, val danger: Color,
) {
    // 65 %: still readable (4.5:1) over the screen backdrop's accent glow, where 55 % measured 3.7:1.
    val textMuted: Color get() = text.copy(alpha = 0.65f)
    /**
     * [c] as text: accent, danger, ok and warn are tuned as fills, and as text over the backdrop glow
     * they fall to 3–4:1. Lifted toward the text colour, every one reads at 4.5:1 or better.
     */
    fun ink(c: Color): Color = androidx.compose.ui.graphics.lerp(c, if (dark) Color.White else Color.Black, if (dark) 0.3f else 0.25f)
    val textLabel: Color get() = text.copy(alpha = 0.70f)
    val divider: Color get() = text.copy(alpha = if (dark) 0.16f else 0.12f)

    companion object {
        /** The default: Nocturne, dark with a soft violet accent. */
        val Nocturne = KPalette(
            dark = true, bg = Color(0xFF161826), surface = Color(0xFF232532), surfaceHi = Color(0xFF2A2C3A), text = Color(0xFFE9E9ED),
            accent = Color(0xFF9184D9), accent2 = Color(0xFFA7A1DB),
            accent100 = Color(0xFFF5F4FF), accent300 = Color(0xFFD2CEFD), accent800 = Color(0xFF423A6A), accent900 = Color(0xFF2B2741),
            neutral100 = Color(0xFFF3F5FE), neutral300 = Color(0xFFCFD3E5), neutral600 = Color(0xFF75798C), neutral700 = Color(0xFF595D6C),
            neutral800 = Color(0xFF3F424D), ok = Color(0xFF7FB69A), warn = Color(0xFFD9C48A), danger = Color(0xFFD98A8A),
        )

        /**
         * A full palette from a brand colour: `KPalette.from(Color(0xFFE91E63), dark = false)`.
         * Tints, selection colours and Material roles are derived so contrast stays readable.
         */
        fun from(accent: Color, accent2: Color? = null, dark: Boolean = true, background: Color? = null,
                 ok: Color? = null, warn: Color? = null, danger: Color? = null, text: Color? = null): KPalette {
            if (dark && accent == Nocturne.accent && accent2 == null && background == null && ok == null && warn == null &&
                danger == null && text == null) return Nocturne
            return if (dark) {
                val bg = background ?: Nocturne.bg
                Nocturne.copy(bg = bg, surface = lerp(bg, Color.White, 0.06f), surfaceHi = lerp(bg, Color.White, 0.10f), text = text ?: Nocturne.text,
                    accent = accent, accent2 = accent2 ?: lerp(accent, Color.White, 0.25f),
                    accent100 = lerp(accent, Color.White, 0.88f), accent300 = lerp(accent, Color.White, 0.6f),
                    accent800 = lerp(accent, bg, 0.62f), accent900 = lerp(accent, bg, 0.78f),
                    ok = ok ?: Nocturne.ok, warn = warn ?: Nocturne.warn, danger = danger ?: Nocturne.danger)
            } else {
                val bg = background ?: Color(0xFFF6F6F9)
                val ink = text ?: Color(0xFF1B1C24)
                KPalette(dark = false, bg = bg, surface = lerp(bg, Color.White, 0.7f), surfaceHi = lerp(bg, ink, 0.05f), text = ink,
                    accent = accent, accent2 = accent2 ?: lerp(accent, Color.Black, 0.2f),
                    accent100 = lerp(accent, Color.Black, 0.55f), accent300 = lerp(accent, Color.Black, 0.3f),
                    accent800 = lerp(accent, Color.White, 0.80f), accent900 = lerp(accent, Color.White, 0.90f),
                    neutral100 = lerp(ink, bg, 0.05f), neutral300 = lerp(ink, bg, 0.25f), neutral600 = lerp(ink, bg, 0.55f),
                    neutral700 = lerp(ink, bg, 0.75f), neutral800 = lerp(ink, bg, 0.87f),
                    ok = ok ?: Color(0xFF2F855A), warn = warn ?: Color(0xFFB7791F), danger = danger ?: Color(0xFFC53030))
            }
        }
    }
}

/** How rounded every component is. */
enum class KCorners(val scale: Float) { Sharp(0.35f), Default(1f), Soft(1.4f), Round(2.2f) }

/** How big and roomy components are: heights, padding and icons of every K component scale with it. */
enum class KDensity(val scale: Float) { Compact(0.85f), Default(1f), Comfortable(1.15f), Large(1.3f) }

/** Ready-made font families for [KilnTheme]; or pass any FontFamily (e.g. from res/font). */
object KFonts {
    val Inter: FontFamily = FontFamily(
        Font(R.font.inter_variable, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.inter_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.inter_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    )
    val Mono: FontFamily = FontFamily(
        Font(R.font.jetbrains_mono_variable, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.jetbrains_mono_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    )
    /** The phone's own font (Roboto or the maker's). */
    val System: FontFamily = FontFamily.Default
    val Serif: FontFamily = FontFamily.Serif
}

/** A named look: palette + font + corners. Pass one to [KilnTheme] (`theme = KThemes.OceanDepths`). */
data class KTheme(val name: String, val palette: KPalette, val font: FontFamily = KFonts.Inter,
                  val corners: KCorners = KCorners.Default, val description: String = "", val style: KStyle = KStyle.Flat)

/**
 * Ready-made themes. Nocturne is Kiln's own and the default whenever the user hasn't asked for a look.
 * The other ten are the presets of Anthropic's theme-factory skill (same names and colours) as app
 * palettes. When the user asks for a style ("ocean-y", "warmer", "minimal", "dark and techy"), pick the
 * closest; for a specific brand colour use `KilnTheme(accent = …)`.
 */
object KThemes {
    val Nocturne: KTheme = KTheme("Nocturne", KPalette.Nocturne, description = "Kiln's default: dark, calm, soft violet")
    val ArcticFrost: KTheme = KTheme("Arctic Frost", KPalette.from(Color(0xFF4A6FA5), Color(0xFF7D9CC9), dark = false,
        background = Color(0xFFF2F6FB), text = Color(0xFF1E2A3A)), corners = KCorners.Soft,
        description = "Cool, crisp, clean: ice blue and steel")
    val BotanicalGarden: KTheme = KTheme("Botanical Garden", KPalette.from(Color(0xFF4A7C59), Color(0xFFF9A620), dark = false,
        background = Color(0xFFF5F3ED), danger = Color(0xFFB7472A), warn = Color(0xFFC98200), text = Color(0xFF2B3A2F)),
        KFonts.Serif, KCorners.Soft, "Fresh, organic: fern green, marigold, terracotta")
    val DesertRose: KTheme = KTheme("Desert Rose", KPalette.from(Color(0xFFB87D6D), Color(0xFFD4A5A5), dark = false,
        background = Color(0xFFF6EDE4), text = Color(0xFF5D2E46)), KFonts.Serif, KCorners.Round,
        "Soft, sophisticated: dusty rose, clay, sand")
    val ForestCanopy: KTheme = KTheme("Forest Canopy", KPalette.from(Color(0xFF2D4A2B), Color(0xFF7D8471), dark = false,
        background = Color(0xFFFAF9F6), text = Color(0xFF1F2A1E)), description = "Grounded, natural: forest green, sage, olive")
    val GoldenHour: KTheme = KTheme("Golden Hour", KPalette.from(Color(0xFFC1666B), Color(0xFFF4A900), dark = false,
        background = Color(0xFFF7EFE3), text = Color(0xFF4A403A)), corners = KCorners.Soft,
        description = "Warm, autumnal: mustard, terracotta, beige")
    val MidnightGalaxy: KTheme = KTheme("Midnight Galaxy", KPalette.from(Color(0xFFA490C2), Color(0xFF8B90D6), dark = true,
        background = Color(0xFF2B1E3E), text = Color(0xFFE6E6FA)), description = "Dramatic, cosmic: deep purple, cosmic blue, lavender")
    val ModernMinimalist: KTheme = KTheme("Modern Minimalist", KPalette.from(Color(0xFF36454F), Color(0xFF708090), dark = false,
        background = Color(0xFFF7F7F7), text = Color(0xFF222B31)), corners = KCorners.Sharp,
        description = "Clean, contemporary: charcoal and greys")
    val OceanDepths: KTheme = KTheme("Ocean Depths", KPalette.from(Color(0xFF2D8B8B), Color(0xFFA8DADC), dark = true,
        background = Color(0xFF1A2332), text = Color(0xFFF1FAEE)), description = "Professional, calming: navy, teal, seafoam")
    val SunsetBoulevard: KTheme = KTheme("Sunset Boulevard", KPalette.from(Color(0xFFE76F51), Color(0xFFF4A261), dark = false,
        background = Color(0xFFFDF6EC), warn = Color(0xFFC99A1E), text = Color(0xFF264653)), corners = KCorners.Round,
        description = "Vibrant, warm: burnt orange, coral, sand")
    val TechInnovation: KTheme = KTheme("Tech Innovation", KPalette.from(Color(0xFF0066FF), Color(0xFF00E5E5), dark = true,
        background = Color(0xFF1E1E1E), text = Color(0xFFFFFFFF)), corners = KCorners.Sharp,
        description = "Bold, modern: electric blue, neon cyan, near-black")

    val all: List<KTheme> = listOf(Nocturne, ArcticFrost, BotanicalGarden, DesertRose, ForestCanopy, GoldenHour,
        MidnightGalaxy, ModernMinimalist, OceanDepths, SunsetBoulevard, TechInnovation)

    /** By name, ignoring case and spaces ("ocean depths", "OceanDepths"); null if unknown. */
    fun named(name: String): KTheme? = all.firstOrNull { it.name.replace(" ", "").equals(name.replace(" ", ""), ignoreCase = true) }
}

/**
 * The active theme tokens. Nocturne unless [KilnTheme] says otherwise; everything that reads them
 * (every K component, and your screens) follows a theme change. Read tokens, never hard-code colours in screens.
 */
object Nocturne {
    internal var palette by mutableStateOf(KPalette.Nocturne)
    internal var cornerScale by mutableFloatStateOf(1f)
    internal var sizeScale by mutableFloatStateOf(1f)
    internal var textScale by mutableFloatStateOf(1f)
    internal var font by mutableStateOf(KFonts.Inter)
    internal var style by mutableStateOf(KStyle.Flat)

    val isDark: Boolean get() = palette.dark
    /** The theme's surface style (Flat, Glass, Neumorphic…). */
    val surfaceStyle: KStyle get() = style
    val bg: Color get() = palette.bg
    val surface: Color get() = palette.surface
    val surfaceHi: Color get() = palette.surfaceHi
    val text: Color get() = palette.text
    val accent: Color get() = palette.accent
    val accent2: Color get() = palette.accent2
    val accent100: Color get() = palette.accent100
    val accent300: Color get() = palette.accent300
    val accent800: Color get() = palette.accent800
    val accent900: Color get() = palette.accent900
    val neutral100: Color get() = palette.neutral100
    val neutral300: Color get() = palette.neutral300
    val neutral600: Color get() = palette.neutral600
    val neutral700: Color get() = palette.neutral700
    val neutral800: Color get() = palette.neutral800
    val ok: Color get() = palette.ok
    val warn: Color get() = palette.warn
    val danger: Color get() = palette.danger
    val textMuted: Color get() = palette.textMuted
    val textLabel: Color get() = palette.textLabel
    /** A colour as text, readable on every surface: `Text("Over budget", color = Nocturne.ink(Nocturne.danger))`. */
    fun ink(c: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color = palette.ink(c)
    val divider: Color get() = palette.divider

    /** The theme's text font. */
    val sans: FontFamily get() = font
    val mono: FontFamily get() = KFonts.Mono
}

/** A corner radius scaled by the theme's [KCorners]: `RoundedCornerShape(kr(12))`. */
fun kr(dp: Int): Dp = (dp * Nocturne.cornerScale).dp

/** A size scaled by the theme's [KDensity]: `Modifier.height(ks(48))`. */
fun ks(dp: Int): Dp = (dp * Nocturne.sizeScale).dp

private fun scheme(p: KPalette): ColorScheme = if (p.dark) darkColorScheme(
    primary = p.accent, onPrimary = p.bg, primaryContainer = p.accent800, onPrimaryContainer = p.accent100,
    secondary = p.accent2, onSecondary = p.bg, secondaryContainer = p.accent900, onSecondaryContainer = p.accent300,
    tertiary = p.ok, onTertiary = p.bg, background = p.bg, onBackground = p.text, surface = p.bg, onSurface = p.text,
    surfaceVariant = p.surface, onSurfaceVariant = p.textLabel, surfaceContainerLowest = p.bg, surfaceContainerLow = p.surface,
    surfaceContainer = p.surface, surfaceContainerHigh = p.surfaceHi, surfaceContainerHighest = p.surfaceHi,
    outline = p.neutral700, outlineVariant = p.neutral800, error = p.danger, onError = p.bg,
    // Every remaining role from the theme too: left out, Material's stock purples showed in snackbars (inverse*),
    // error banners (errorContainer), tertiary chips and elevated surfaces, whatever the app's theme.
    tertiaryContainer = p.surfaceHi, onTertiaryContainer = p.ok, errorContainer = p.surfaceHi, onErrorContainer = p.danger,
    inverseSurface = p.text, inverseOnSurface = p.bg, inversePrimary = p.accent800,
    surfaceBright = p.surfaceHi, surfaceDim = p.bg, scrim = Color.Black,
) else lightColorScheme(
    primary = p.accent, onPrimary = Color.White, primaryContainer = p.accent800, onPrimaryContainer = p.accent100,
    secondary = p.accent2, onSecondary = Color.White, secondaryContainer = p.accent900, onSecondaryContainer = p.accent300,
    tertiary = p.ok, onTertiary = Color.White, background = p.bg, onBackground = p.text, surface = p.bg, onSurface = p.text,
    surfaceVariant = p.surfaceHi, onSurfaceVariant = p.textLabel, surfaceContainerLowest = Color.White, surfaceContainerLow = p.surface,
    surfaceContainer = p.surface, surfaceContainerHigh = p.surfaceHi, surfaceContainerHighest = p.surfaceHi,
    outline = p.neutral700, outlineVariant = p.neutral800, error = p.danger, onError = Color.White,
    tertiaryContainer = p.surfaceHi, onTertiaryContainer = p.ok, errorContainer = p.surfaceHi, onErrorContainer = p.danger,
    inverseSurface = p.text, inverseOnSurface = p.bg, inversePrimary = p.accent300,
    surfaceBright = Color.White, surfaceDim = p.surface, scrim = Color.Black,
)

private fun typography(font: FontFamily, scale: Float): Typography {
    fun s(size: Int, line: Int, w: FontWeight = FontWeight.Normal, track: Double = 0.0) =
        TextStyle(fontFamily = font, fontWeight = w, fontSize = (size * scale).sp, lineHeight = (line * scale).sp, letterSpacing = track.em)
    return Typography(
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
}

private fun shapes() = Shapes(
    extraSmall = RoundedCornerShape(kr(4)), small = RoundedCornerShape(kr(6)),
    medium = RoundedCornerShape(kr(8)), large = RoundedCornerShape(kr(14)), extraLarge = RoundedCornerShape(kr(20)),
)

/**
 * Wrap the app in this. With no arguments it's Nocturne, Kiln's own theme — keep it unless the user
 * asks for a different look. Restyle every component at once:
 * ```
 * KilnTheme(theme = KThemes.OceanDepths) { App() }                                      // a preset
 * KilnTheme(accent = Color(0xFFE91E63), dark = false, font = KFonts.System) { App() }    // a brand colour
 * KilnTheme(theme = KThemes.SunsetBoulevard, density = KDensity.Comfortable, textScale = 1.1f, surfaceAlpha = 0.85f) { … }
 * ```
 * Explicit arguments override the [theme]'s. [density] scales component heights, padding and icons;
 * [textScale] all text; [surfaceAlpha] (0–1) makes cards, fields and sheets translucent, for a
 * background image or gradient. [style] draws every surface as Flat, Glass (glassmorphism), Neumorphic,
 * Outlined, Elevated or Brutalist. [palette] replaces the colours entirely.
 */
@Composable
fun KilnTheme(
    theme: KTheme = KThemes.Nocturne,
    accent: Color? = null,
    accent2: Color? = null,
    dark: Boolean? = null,
    background: Color? = null,
    font: FontFamily? = null,
    corners: KCorners? = null,
    density: KDensity = KDensity.Default,
    textScale: Float = 1f,
    surfaceAlpha: Float = 1f,
    style: KStyle? = null,
    palette: KPalette? = null,
    content: @Composable () -> Unit,
) {
    val st = style ?: theme.style
    val p = remember(theme, accent, accent2, dark, background, palette, surfaceAlpha) {
        val base = palette ?: if (accent == null && accent2 == null && dark == null && background == null) theme.palette else {
            val sameMode = dark == null || dark == theme.palette.dark
            KPalette.from(accent ?: theme.palette.accent, accent2 ?: if (accent == null) theme.palette.accent2 else null,
                dark ?: theme.palette.dark, background ?: if (sameMode) theme.palette.bg else null,
                text = if (sameMode) theme.palette.text else null)
        }
        val a = surfaceAlpha.coerceIn(0f, 1f)
        if (a >= 1f) base else base.copy(surface = base.surface.copy(alpha = a), surfaceHi = base.surfaceHi.copy(alpha = a))
    }
    val f = font ?: theme.font
    val c = corners ?: when (st) { KStyle.Brutalist -> KCorners.Sharp; KStyle.Clay -> KCorners.Round; else -> theme.corners }
    // Publish before children compose, so Nocturne.* reads and K components see this theme.
    remember(p, f, c, density, textScale, st) {
        Nocturne.palette = p; Nocturne.font = f; Nocturne.cornerScale = c.scale; Nocturne.style = st
        Nocturne.sizeScale = density.scale; Nocturne.textScale = textScale; Unit
    }
    // Status and navigation bar icons dark on a light theme, light on a dark one.
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? android.app.Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = !p.dark; isAppearanceLightNavigationBars = !p.dark }
        }
    }
    val scheme = remember(p) { scheme(p) }
    val type = remember(f, textScale) { typography(f, textScale) }
    val shapes = remember(c) { shapes() }
    // Plain Text outside a kit surface took Compose's default content colour, black: "Edit Expense" was nearly invisible
    // on the dark background. The theme's text colour is the default everywhere; K components still set their own.
    MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides p.text, content = content)
    }
}

/** A text size scaled by the theme's `textScale`: `fontSize = kt(14)`. */
fun kt(sp: Int): androidx.compose.ui.unit.TextUnit = (sp * Nocturne.textScale).sp
