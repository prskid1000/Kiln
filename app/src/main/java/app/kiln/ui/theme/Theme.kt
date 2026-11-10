@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package app.kiln.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.kiln.R

/**
 * Nocturne — transcribed from the On-Device AI design system's Nocturne
 * stylesheet, the shared source of truth with Vessel.
 * Heading weight 500, hairline-ring + ambient-shadow elevation, accent kickers.
 */
object N {
    val bg = Color(0xFF0C0C0C)
    val surface = Color(0xFF1A1D1A)
    val surfaceHi = Color(0xFF222622)
    val text = Color(0xFFDDE3DC)
    val accent = Color(0xFF8FBF7F)
    val accent2 = Color(0xFFA8BEA7)

    // neutral ramp
    val neutral100 = Color(0xFFF2F5F1)
    val neutral600 = Color(0xFF727C72)
    val neutral700 = Color(0xFF565F56)
    val neutral800 = Color(0xFF3D433D)
    // accent ramp
    val accent100 = Color(0xFFF1F7EF)
    val accent800 = Color(0xFF42583B)
    // status
    val ok = Color(0xFF9FD48C)
    val danger = Color(0xFFDC9089)
    val warn = Color(0xFFE0CE8A)
    // Nocturne "section" indigo — used for the header glow band.
    val section = Color(0xFF233023)
    val sectionGlow = Color(0xFF324332)

    // derived
    val textMuted = text.copy(alpha = 0.55f)
    val textLabel = text.copy(alpha = 0.70f)
    val divider = text.copy(alpha = 0.16f)
    val cardRing = neutral700          // --shadow-md ring
    val cardRingSm = neutral800        // --shadow-sm ring
    val statusGround = 0.16f

    // spacing (design tokens, rounded to dp)
    val s2 = 6.dp; val s3 = 8.dp; val s4 = 11.dp; val s6 = 17.dp; val s8 = 22.dp

    // shapes
    val shapeSm: Shape = RoundedCornerShape(4.dp)
    val shapeMd: Shape = RoundedCornerShape(8.dp)
    val shapeLg: Shape = RoundedCornerShape(14.dp)
    val shapeTag: Shape = RoundedCornerShape(6.dp)
}

private val Heading = FontWeight.Medium   // --font-heading-weight: 500

private val Sans = FontFamily(
    Font(R.font.inter_variable, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter_variable, Heading, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)
private val Mono = FontFamily(
    Font(R.font.jetbrains_mono_variable, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.jetbrains_mono_variable, Heading, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
)

/** Type scale from the Nocturne stylesheet (h-scale + body 15/1.55). */
object T {
    val h3 = TextStyle(fontFamily = Sans, fontWeight = Heading, fontSize = 25.sp, lineHeight = 28.sp, letterSpacing = (-0.015).em, color = N.text)
    val h4 = TextStyle(fontFamily = Sans, fontWeight = Heading, fontSize = 20.sp, lineHeight = 23.sp, letterSpacing = (-0.015).em, color = N.text)
    val cardTitle = TextStyle(fontFamily = Sans, fontWeight = Heading, fontSize = 17.sp, lineHeight = 20.sp, letterSpacing = (-0.01).em, color = N.text)
    val subtitle = TextStyle(fontFamily = Sans, fontWeight = Heading, fontSize = 15.sp, lineHeight = 19.sp, color = N.text)
    val body = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp, color = N.text)
    val bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, color = N.text.copy(alpha = 0.8f))
    val label = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 15.sp, color = N.textLabel)
    val kicker = TextStyle(fontFamily = Sans, fontWeight = Heading, fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.10.em, color = N.accent)
    val overline = TextStyle(fontFamily = Sans, fontWeight = Heading, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.08.em, color = N.textMuted)
    val mono = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp, color = N.textLabel)
    val monoSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 10.5.sp, lineHeight = 14.sp, color = N.textMuted)
    val control = TextStyle(fontFamily = Sans, fontWeight = Heading, fontSize = 14.sp, lineHeight = 17.sp, color = N.text)
}

/**
 * `vCard()` — surface with the Nocturne `--shadow-md` elevation: a solid
 * neutral hairline ring plus an ambient drop shadow. This is the depth my first
 * pass was missing.
 */
fun Modifier.vCard(shape: Shape = N.shapeMd, ring: Color = N.cardRing): Modifier =
    this.shadow(12.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
        .clip(shape).background(N.surface).border(1.dp, ring, shape)

/** Inset field/code well: bg ground with a divider hairline. */
fun Modifier.vInset(shape: Shape = N.shapeMd): Modifier =
    this.clip(shape).background(N.bg).border(1.dp, N.divider, shape)

/** Accent kicker (Nocturne `.card-kicker`). */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), style = T.kicker, modifier = modifier)

@Composable
fun Overline(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), style = T.overline, modifier = modifier)

private val KilnColors = darkColorScheme(
    background = N.bg, surface = N.surface, primary = N.accent, secondary = N.accent2,
    onBackground = N.text, onSurface = N.text, onPrimary = N.bg,
)

@Composable
fun KilnTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = KilnColors, content = content)
