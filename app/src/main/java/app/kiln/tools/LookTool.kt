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
    /** A surface style: its KStyle code, the name people know, and a few words. */
    data class LookStyle(val code: String, val label: String, val hint: String)
    val styles = listOf(
        LookStyle("Flat", "Flat", "solid, simple"),
        LookStyle("Glass", "Glass", "Glassmorphism · frosted glass over a gradient"),
        LookStyle("Neumorphic", "Neumorphic", "Neumorphism · soft extruded, best on light themes"),
        LookStyle("Clay", "Clay", "Claymorphism · puffy pastel 3D, very rounded"),
        LookStyle("Elevated", "Elevated", "Material · cards floating on shadows"),
        LookStyle("Outlined", "Outlined", "borders only, airy"),
        LookStyle("Skeuomorphic", "Skeuo", "Skeuomorphism · gradients and bevels, real-object feel"),
        LookStyle("Neon", "Neon", "Cyberpunk · glowing edges, best on dark themes"),
        LookStyle("Brutalist", "Brutalist", "Neo-brutalism · bold borders, hard shadows"),
    )
    const val DEFAULT = "default"

    /** The answer the picker sends: "theme=Ocean Depths; style=Glass". */
    fun answer(theme: String, style: String) = "theme=$theme; style=$style"

    /** Marks a project created in Kiln whose look the user hasn't picked yet (asked at its first run). */
    const val PENDING = "look-pending"

    /**
     * Write the chosen theme into the project's MainActivity (a Theme override), so it doesn't depend
     * on the model applying it. Returns false when the answer isn't a preset or MainActivity can't be found.
     */
    fun applyTo(project: app.kiln.build.Project, answer: String): Boolean {
        val theme = Regex("theme=([^;]+)").find(answer)?.groupValues?.get(1)?.trim() ?: return false
        val style = Regex("style=([^;]+)").find(answer)?.groupValues?.get(1)?.trim() ?: "Flat"
        val t = themes.firstOrNull { it.name.equals(theme, true) } ?: return false
        if (t.code == "Nocturne" && style == "Flat") return false
        val f = project.files().firstOrNull { it.name == "MainActivity.kt" } ?: return false
        var s = f.readText()
        val cls = Regex("""class\s+MainActivity\s*:\s*KilnActivity\(\)\s*\{""").find(s) ?: return false
        if ("override fun Theme(" in s) return false
        val args = listOfNotNull("theme = KThemes.${t.code}", if (style != "Flat") "style = KStyle.$style" else null).joinToString(", ")
        s = s.substring(0, cls.range.last + 1) +
            "\n    // The look the user picked when the app was created.\n" +
            "    @Composable\n    override fun Theme(content: @Composable () -> Unit) = KilnTheme($args, content = content)\n" +
            s.substring(cls.range.last + 1)
        val imports = listOfNotNull("app.kiln.kit.KilnTheme", "app.kiln.kit.KThemes", if (style != "Flat") "app.kiln.kit.KStyle" else null,
            "androidx.compose.runtime.Composable").filter { "import $it\n" !in s }
        val pkg = Regex("""(?m)^package .+\n""").find(s)
        if (imports.isNotEmpty() && pkg != null)
            s = s.substring(0, pkg.range.last + 1) + "\n" + imports.joinToString("") { "import $it\n" } + s.substring(pkg.range.last + 1)
        f.writeText(s)
        return true
    }

    /** What the agent should do with the picker's answer, and a short summary. */
    fun instruction(answer: String): Pair<String, String> {
        val raw = answer.trim()
        val theme = Regex("theme=([^;]+)").find(raw)?.groupValues?.get(1)?.trim()
        val style = Regex("style=([^;]+)").find(raw)?.groupValues?.get(1)?.trim() ?: "Flat"
        val t = themes.firstOrNull { it.name.equals(theme, true) }
        val keepDefault = raw.equals(DEFAULT, true) || raw.isBlank() || raw.startsWith("no user", true) ||
            (t?.code == "Nocturne" && style == "Flat")
        fun hex(c: Long) = "%06X".format(c and 0xFFFFFF)
        return when {
            keepDefault -> "Keep Kiln's default look (Nocturne, Flat): don't override Theme in MainActivity." to "Look: Kiln default"
            t != null -> {
                val args = listOfNotNull("theme = KThemes.${t.code}", if (style != "Flat") "style = KStyle.$style" else null).joinToString(", ")
                ("The user chose ${t.name} (${t.mood})${if (style != "Flat") " with the $style style" else ""}. In MainActivity add:\n" +
                    "    @Composable override fun Theme(content: @Composable () -> Unit) = KilnTheme($args, content = content)\n" +
                    "Read colours only from Nocturne.* tokens in screens. Draw the launcher icon in this palette " +
                    "(bg #${hex(t.bg)}, accent #${hex(t.accent)}, accent2 #${hex(t.accent2)}).") to
                    "Look: ${t.name}${if (style != "Flat") " · $style" else ""}"
            }
            else -> ("The user described the look in their own words: \"$raw\". Map it to the closest KThemes preset, " +
                "or KilnTheme(accent = …, dark = …) for a specific colour, plus a KStyle if they mention one (load_skill theming). " +
                "Then override Theme in MainActivity.") to "Look: \"${raw.take(40)}\""
        }
    }
}

/**
 * Asks the user, with a visual picker, how a new app should look: one of the kit's themes and a
 * surface style (or Kiln's default, or their own words). Answers with the exact Theme override.
 */
class ChooseLookTool : Tool {
    override val name = "choose_look"
    override val description = "Show the user a visual picker for the app's look (theme + surface style such as glass or " +
        "neumorphic) and get their choice with the exact code. Kiln already asks at the start of a new app; call this only " +
        "when the user asks to pick or change the look and hasn't said what they want."
    override val schema = schema { str("app", "What the app is, in a few words (shown on the picker).", required = false) }
    override val traits = setOf(Trait.READ_ONLY)
    override val timeoutMs = 24 * 3_600_000L

    override suspend fun run(ctx: ToolContext, input: JsonObject): ToolResult {
        val (text, summary) = Looks.instruction(ctx.chooseLook(input.str("app")))
        return ToolResult.ok(text, summary)
    }
}
