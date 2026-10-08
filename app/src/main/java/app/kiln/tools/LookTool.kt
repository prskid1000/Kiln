package app.kiln.tools

import app.kiln.core.str
import kotlinx.serialization.json.JsonObject

/** A theme as the look picker shows it (mirrors the kit's KThemes: name, background, accents). */
data class LookTheme(val name: String, val code: String, val bg: Long, val accent: Long, val accent2: Long, val dark: Boolean, val mood: String)

/** The kit's themes and surface styles, for the picker card and the tool's answer. */
object Looks {
    val themes = listOf(
        LookTheme("Nocturne", "Nocturne", 0xFF161826, 0xFF9184D9, 0xFFA7A1DB, true, "Kiln default · calm dark violet"),
        LookTheme("Arctic Frost", "ArcticFrost", 0xFFF2F6FB, 0xFF4A6FA5, 0xFF7D9CC9, false, "cool, crisp, clean"),
        LookTheme("Botanical Garden", "BotanicalGarden", 0xFFF5F3ED, 0xFF4A7C59, 0xFFF9A620, false, "fresh, organic"),
        LookTheme("Desert Rose", "DesertRose", 0xFFF6EDE4, 0xFFB87D6D, 0xFFD4A5A5, false, "soft, elegant"),
        LookTheme("Forest Canopy", "ForestCanopy", 0xFFFAF9F6, 0xFF2D4A2B, 0xFF7D8471, false, "grounded, natural"),
        LookTheme("Golden Hour", "GoldenHour", 0xFFF7EFE3, 0xFFC1666B, 0xFFF4A900, false, "warm, cosy"),
        LookTheme("Midnight Galaxy", "MidnightGalaxy", 0xFF2B1E3E, 0xFFA490C2, 0xFF8B90D6, true, "dramatic, dreamy"),
        LookTheme("Modern Minimalist", "ModernMinimalist", 0xFFF7F7F7, 0xFF36454F, 0xFF708090, false, "clean, neutral"),
        LookTheme("Ocean Depths", "OceanDepths", 0xFF1A2332, 0xFF2D8B8B, 0xFFA8DADC, true, "professional, calming"),
        LookTheme("Sunset Boulevard", "SunsetBoulevard", 0xFFFDF6EC, 0xFFE76F51, 0xFFF4A261, false, "vibrant, warm"),
        LookTheme("Tech Innovation", "TechInnovation", 0xFF1E1E1E, 0xFF0066FF, 0xFF00E5E5, true, "bold, techy"),
    )
    /** KStyle names with a few words each. */
    val styles = listOf(
        "Flat" to "solid, simple", "Glass" to "frosted glass", "Neumorphic" to "soft extruded",
        "Outlined" to "borders only", "Elevated" to "floating shadows", "Brutalist" to "bold, hard shadows",
    )
    const val DEFAULT = "default"

    /** The answer the picker sends: "theme=Ocean Depths; style=Glass". */
    fun answer(theme: String, style: String) = "theme=$theme; style=$style"
}

/**
 * Asks the user, with a visual picker, how a new app should look: one of the kit's themes and a
 * surface style (or Kiln's default, or their own words). Answers with the exact Theme override.
 */
class ChooseLookTool : Tool {
    override val name = "choose_look"
    override val description = "Show the user a visual picker for a NEW app's look (theme + surface style such as glass or " +
        "neumorphic) and get their choice with the exact code. Call it once at the start of a new app when the user hasn't " +
        "described the look; not for edits, and not if they already said (then map their words yourself, see the theming skill)."
    override val schema = schema { str("app", "What the app is, in a few words (shown on the picker).", required = false) }
    override val traits = setOf(Trait.READ_ONLY)
    override val timeoutMs = 24 * 3_600_000L

    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val raw = ctx.chooseLook(input.str("app")).trim()
        val theme = Regex("theme=([^;]+)").find(raw)?.groupValues?.get(1)?.trim()
        val style = Regex("style=([^;]+)").find(raw)?.groupValues?.get(1)?.trim() ?: "Flat"
        val t = Looks.themes.firstOrNull { it.name.equals(theme, true) }
        val keepDefault = raw.equals(Looks.DEFAULT, true) || raw.isBlank() || raw.startsWith("no user", true) ||
            (t?.code == "Nocturne" && style == "Flat")
        return when {
            keepDefault -> ToolResult.ok("Keep Kiln's default look (Nocturne, Flat): don't override Theme in MainActivity.", "Look: Kiln default")
            t != null -> {
                val args = listOfNotNull("theme = KThemes.${t.code}", if (style != "Flat") "style = KStyle.$style" else null).joinToString(", ")
                ToolResult.ok("The user chose ${t.name} (${t.mood})${if (style != "Flat") " with the $style style" else ""}. In MainActivity add:\n" +
                    "    @Composable override fun Theme(content: @Composable () -> Unit) = KilnTheme($args, content = content)\n" +
                    "Read colours only from Nocturne.* tokens in screens. Draw the launcher icon in this palette " +
                    "(bg #${hex(t.bg)}, accent #${hex(t.accent)}, accent2 #${hex(t.accent2)}).", "Look: ${t.name}${if (style != "Flat") " · $style" else ""}")
            }
            else -> ToolResult.ok("The user described the look in their own words: \"$raw\". Map it to the closest KThemes preset, " +
                "or KilnTheme(accent = …, dark = …) for a specific colour, plus a KStyle if they mention one (load_skill theming). " +
                "Then override Theme in MainActivity.", "Look: \"${raw.take(40)}\"")
        }
    }

    private fun hex(c: Long) = "%06X".format(c and 0xFFFFFF)
}
