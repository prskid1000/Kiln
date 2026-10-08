package app.kiln.kit

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How surfaces (cards, buttons, fields, chips, alerts, segmented controls) are drawn, app-wide — the
 * visual style: flat, glassmorphism (Glass), neumorphism / soft UI (Neumorphic), outlined, elevated
 * (material shadows), neo-brutalism (Brutalist).
 * Set it on the theme: `KilnTheme(style = KStyle.Glass)` or with a preset `KilnTheme(theme = …, style = …)`.
 */
enum class KStyle(val description: String) {
    /** Solid fills, hairline borders. Kiln's default. */
    Flat("solid fills, hairline borders"),
    /** Glassmorphism: frosted translucent panes with light edges over a colourful gradient backdrop. */
    Glass("frosted translucent panes, light edges, gradient backdrop"),
    /** Neumorphism (soft UI): surfaces extruded from the background by a light and a dark shadow. */
    Neumorphic("soft extruded surfaces, paired light/dark shadows, no borders"),
    /** Borders only, no fills on cards and fields: airy, wireframe-like. */
    Outlined("borders only, transparent cards"),
    /** Material-style elevation: cards float on soft shadows, no borders. */
    Elevated("cards lifted by soft shadows"),
    /** Neo-brutalism: thick ink borders, hard offset shadows, sharp corners. */
    Brutalist("thick borders, hard offset shadows, sharp corners"),
}

/**
 * Draw a surface in the theme's [KStyle]: background, border, shadow. [neutral] marks a plain
 * container (card, field) as opposed to a coloured one (a filled button, a tinted alert), which
 * keeps its colour in every style. Use it for your own surfaces so they match the K components.
 */
fun Modifier.kSurface(shape: Shape, fill: Color, border: Color? = null, neutral: Boolean = true, borderWidth: Dp = 1.dp): Modifier {
    val dark = Nocturne.isDark
    return when (Nocturne.style) {
        KStyle.Flat -> this.clip(shape).background(fill).then(if (border != null) Modifier.border(borderWidth, border, shape) else Modifier)
        KStyle.Outlined -> this.clip(shape).background(if (neutral) Color.Transparent else fill)
            .border(maxOf(borderWidth, 1.5.dp), border ?: Nocturne.text.copy(alpha = 0.35f), shape)
        KStyle.Elevated -> this.shadow(if (neutral) 6.dp else 3.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.35f))
            .clip(shape).background(if (neutral && fill.alpha < 1f) Nocturne.surface.copy(alpha = 1f) else fill.compositeOver(Nocturne.bg.copy(alpha = 1f)))
        KStyle.Glass -> {
            val pane = if (neutral) (if (dark) Color.White.copy(alpha = 0.09f) else Color.White.copy(alpha = 0.45f)) else fill.copy(alpha = fill.alpha * 0.82f)
            val edge = Brush.linearGradient(listOf(Color.White.copy(alpha = if (dark) 0.38f else 0.85f), Color.White.copy(alpha = if (dark) 0.06f else 0.25f)))
            val shine = Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.10f else 0.25f), Color.Transparent))
            // No drop shadow: it would show through the translucent pane as an inner box.
            this
                .clip(shape).background(pane).background(shine).border(1.dp, edge, shape)
        }
        KStyle.Neumorphic -> {
            val base = if (neutral) Nocturne.bg.copy(alpha = 1f) else fill.compositeOver(Nocturne.bg.copy(alpha = 1f))
            val light = if (dark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.95f)
            val shade = if (dark) Color.Black.copy(alpha = 0.55f) else Color(0xFFA3B1C6).copy(alpha = 0.6f)
            this.drawBehind {
                val outline = shape.createOutline(size, layoutDirection, this)
                val d = 6.dp.toPx(); val blur = 10.dp.toPx()
                for ((c, off) in listOf(light to -d, shade to d)) drawIntoCanvas { canvas ->
                    val p = Paint().apply { asFrameworkPaint().apply { isAntiAlias = true; color = c.toArgb(); maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) } }
                    canvas.save(); canvas.translate(off, off); canvas.drawOutline(outline, p); canvas.restore()
                }
            }.clip(shape).background(base)
        }
        KStyle.Brutalist -> {
            // Bold outlines and hard shadows in the theme's own colour (not black/white): a deep accent on
            // light themes, a light accent on dark ones.
            val ink = if (Nocturne.isDark) androidx.compose.ui.graphics.lerp(Nocturne.accent, Color.White, 0.35f)
                else androidx.compose.ui.graphics.lerp(Nocturne.accent, Nocturne.text, 0.45f)
            val drop = if (Nocturne.isDark) Nocturne.accent.copy(alpha = 0.9f) else ink
            this.drawBehind {
                val outline = shape.createOutline(size, layoutDirection, this)
                val o = 4.dp.toPx()
                translate(o, o) { drawOutline(outline, drop) }
            }.clip(shape).background(if (neutral && fill.alpha < 1f) Nocturne.surface.copy(alpha = 1f) else fill.compositeOver(Nocturne.bg.copy(alpha = 1f)))
                .border(2.dp, ink, shape)
        }
    }
}

/**
 * The screen background in the theme's style: plain bg, or for [KStyle.Glass] a soft gradient with
 * colour blooms for the glass to frost. KilnScreen and KilnTabs use it; use it for custom full-screen layouts.
 */
fun Modifier.kBackdrop(): Modifier {
    val bg = Nocturne.bg
    if (Nocturne.style != KStyle.Glass) return this.background(bg)
    val a = Nocturne.accent; val b = Nocturne.accent2
    return this.background(bg).drawBehind {
        drawRect(Brush.linearGradient(listOf(a.copy(alpha = 0.55f), bg, b.copy(alpha = 0.45f)), Offset.Zero, Offset(size.width, size.height)))
        drawCircle(Brush.radialGradient(listOf(a.copy(alpha = 0.55f), Color.Transparent), Offset(size.width * 0.15f, size.height * 0.2f), size.minDimension * 0.6f),
            size.minDimension * 0.6f, Offset(size.width * 0.15f, size.height * 0.2f))
        drawCircle(Brush.radialGradient(listOf(b.copy(alpha = 0.5f), Color.Transparent), Offset(size.width * 0.9f, size.height * 0.75f), size.minDimension * 0.7f),
            size.minDimension * 0.7f, Offset(size.width * 0.9f, size.height * 0.75f))
    }
}
