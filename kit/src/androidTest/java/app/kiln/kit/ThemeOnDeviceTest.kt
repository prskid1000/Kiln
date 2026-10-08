package app.kiln.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Themes, density, text scale, translucency and per-component overrides — checked by pixel on the device. */
@RunWith(AndroidJUnit4::class)
class ThemeOnDeviceTest {
    @get:Rule val rule = createComposeRule()

    @After fun reset() { Nocturne.palette = KPalette.Nocturne; Nocturne.sizeScale = 1f; Nocturne.textScale = 1f; Nocturne.cornerScale = 1f; Nocturne.font = KFonts.Inter }

    private fun shot(name: String) {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The colour at the middle-left inside a node (clear of its text). */
    private fun pixel(tag: String, fx: Float = 0.125f, fy: Float = 0.5f): Int {
        val bmp = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        return bmp.getPixel((bmp.width * fx).toInt(), (bmp.height * fy).toInt())
    }

    private fun near(expected: Color, actual: Int, tol: Int = 6): Boolean {
        val e = expected.toArgb()
        return listOf(16, 8, 0).all { sh -> abs(((e shr sh) and 0xFF) - ((actual shr sh) and 0xFF)) <= tol }
    }

    @Test fun everyPresetRendersWithItsColours() {
        var theme by mutableStateOf(KThemes.Nocturne)
        rule.setContent {
            KilnTheme(theme = theme) {
                Surface(Modifier.fillMaxSize(), color = Nocturne.bg) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(theme.name, style = MaterialTheme.typography.headlineSmall)
                        KButton("Primary", Modifier.testTag("btn"), icon = Icons.Filled.Add, width = 220.dp) {}
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            KButton("Tonal", variant = KVariant.Tonal) {}; KButton("Outline", variant = KVariant.Outline) {}
                        }
                        KCardBox { Text("A card on ${if (Nocturne.isDark) "dark" else "light"}"); KTag("Active", KTone.Ok) }
                        KTextField("Typed text", {}, label = "Field")
                        KSegmented(listOf("Day", "Week"), 0, {})
                        KProgressBar(0.6f, label = "Progress")
                        KAlert("Heads up", tone = KTone.Warn)
                        KBarChart(listOf(2f, 5f, 3f), listOf("A", "B", "C"), height = 90.dp)
                    }
                }
            }
        }
        for (t in KThemes.all) {
            rule.runOnIdle { theme = t }
            rule.waitForIdle()
            rule.onNodeWithText(t.name).assertExists()
            assertTrue("${t.name}: button isn't the theme accent", near(t.palette.accent, pixel("btn")))
            assertEquals(t.palette.dark, Nocturne.isDark)
            shot("theme_" + t.name.replace(" ", "_").lowercase())
        }
        assertNotNull(KThemes.named("ocean depths")); assertNotNull(KThemes.named("TechInnovation"))
        assertEquals(11, KThemes.all.size)
    }

    @Test fun brandColourLightModeDensityTextScaleAndOverrides() {
        val pink = Color(0xFFE91E63)
        var density by mutableStateOf(KDensity.Default)
        var heights = mutableMapOf<KDensity, Float>()
        rule.setContent {
            KilnTheme(accent = pink, dark = false, density = density, textScale = 1.2f, corners = KCorners.Round) {
                Surface(Modifier.fillMaxSize(), color = Nocturne.bg) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        KButton("Brand", Modifier.testTag("brand"), width = 200.dp) {}
                        KButton("Override", Modifier.testTag("override"), width = 200.dp,
                            colors = KColors(container = Color(0xFF2E7D32), content = Color.White)) {}
                        KCardBox(Modifier.testTag("card"), colors = KColors(container = Color(0xFF1565C0)), height = 60.dp) { Text("Override card") }
                        KChip("Chip", colors = KColors(container = Color(0xFFFFEB3B), content = Color.Black))
                        KProgressBar(0.5f, color = Color(0xFF00BCD4))
                    }
                }
            }
        }
        assertTrue("brand accent", near(pink, pixel("brand")))
        assertTrue("override container", near(Color(0xFF2E7D32), pixel("override")))
        assertTrue("card override", near(Color(0xFF1565C0), pixel("card", 0.5f, 0.85f)))
        assertTrue("light theme background", !Nocturne.isDark && Nocturne.bg.red > 0.9f)
        assertEquals(1.2f, Nocturne.textScale)
        shot("theme_brand_light")
        for (d in listOf(KDensity.Compact, KDensity.Default, KDensity.Large)) {
            rule.runOnIdle { density = d }; rule.waitForIdle()
            heights[d] = rule.onNodeWithTag("brand").fetchSemanticsNode().size.height.toFloat()
        }
        assertTrue("density scales height: $heights", heights[KDensity.Compact]!! < heights[KDensity.Default]!! && heights[KDensity.Default]!! < heights[KDensity.Large]!!)
        assertEquals(1.3f / 0.85f, heights[KDensity.Large]!! / heights[KDensity.Compact]!!, 0.08f)
    }

    @Test fun translucentSurfacesShowWhatIsBehind() {
        rule.setContent {
            KilnTheme(surfaceAlpha = 0.5f) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().padding(0.dp)) { Surface(Modifier.fillMaxSize(), color = Color.Red) {} }
                    Column(Modifier.padding(24.dp)) { KCardBox(Modifier.testTag("glass"), variant = KVariant.Tonal, height = 80.dp) { Text("Glass") } }
                }
            }
        }
        val px = pixel("glass")
        val red = (px shr 16) and 0xFF
        assertTrue("red should show through the card, got red=$red", red > 100)
        shot("theme_translucent")
    }

    @Test fun everySurfaceStyleRenders() {
        var style by mutableStateOf(KStyle.Flat)
        var theme by mutableStateOf(KThemes.Nocturne)
        rule.setContent {
            KilnTheme(theme = theme, style = style) {
                KilnScreen("Style: ${style.name}") { pad ->
                    Column(Modifier.screenPadding(pad), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        KCardBox(Modifier.testTag("card"), height = 90.dp) { Text("Card"); KTag("Tag", KTone.Ok) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            KButton("Primary") {}; KButton("Tonal", variant = KVariant.Tonal) {}; KButton("Outline", variant = KVariant.Outline) {}
                        }
                        KSearchBar("", {}, placeholder = "Search")
                        KSegmented(listOf("Day", "Week", "Month"), 1, {})
                        KSelect(listOf("One"), null, {}, label = "Select")
                        KAlert("An alert", tone = KTone.Warn)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { KStat("Steps", "8,412", Modifier.weight(1f)); KStat("Kcal", "512", Modifier.weight(1f)) }
                        KChip("Chip", selected = true)
                    }
                }
            }
        }
        for (s in KStyle.entries) for (t in listOf(KThemes.Nocturne, KThemes.ArcticFrost)) {
            rule.runOnIdle { style = s; theme = t }; rule.waitForIdle()
            assertEquals(s, Nocturne.surfaceStyle)
            val bmp = rule.onNodeWithTag("card").captureToImage().asAndroidBitmap()
            val mid = bmp.getPixel(bmp.width / 2, bmp.height - 6)
            when (s) {
                KStyle.Outlined -> assertTrue("outlined card is see-through", near(Nocturne.bg, mid, 10))
                KStyle.Glass -> assertTrue("glass card isn't the flat surface", !near(Nocturne.surface, mid, 3))
                else -> {}
            }
            shot("style_${s.name.lowercase()}_${t.name.replace(" ", "_").lowercase()}")
        }
        rule.runOnIdle { style = KStyle.Brutalist }; rule.waitForIdle()
        assertEquals(KCorners.Sharp.scale, Nocturne.cornerScale)            // brutalist forces sharp corners
    }

    @Test fun themePickerSwitchesTheApp() {
        var name by mutableStateOf(KThemes.Nocturne.name)
        rule.setContent {
            KilnTheme(theme = KThemes.named(name) ?: KThemes.Nocturne) {
                Surface(Modifier.fillMaxSize(), color = Nocturne.bg) {
                    Column(Modifier.padding(16.dp)) { KThemePicker(name, { name = it.name }) }
                }
            }
        }
        shot("theme_picker")
        rule.onNodeWithText("Ocean Depths").performClick()
        rule.waitForIdle()
        assertEquals("Ocean Depths", name); assertEquals(KThemes.OceanDepths.palette.bg, Nocturne.bg)
        rule.onNodeWithText("Sunset Boulevard").performClick(); rule.waitForIdle()
        assertTrue(!Nocturne.isDark)
    }
}
