package app.kiln.build

/**
 * A distinct starter launcher icon per project, derived from its name: Nocturne background, one
 * bold shape and colour picked by hash. It's a placeholder until the agent draws a real one, but
 * two new apps never share the same icon.
 */
object DefaultIcon {
    private val colors = listOf("#9184D9", "#7FB69A", "#D9A48A", "#84AED9", "#C98AD9", "#D9C48A", "#A7A1DB", "#D98A8A")

    /** Glyphs in the 108×108 viewport, filling most of the 30–78 safe zone. */
    private val shapes = listOf(
        "M54,28 L80,54 L54,80 L28,54 Z",                                                    // diamond
        "M54,28 A26,26 0 1 1 53.99,28 Z M54,42 A12,12 0 1 0 54.01,42 Z",                     // ring
        "M54,27 L60.5,45 L80,45 L64.5,56.5 L70.5,76 L54,64.5 L37.5,76 L43.5,56.5 L28,45 L47.5,45 Z", // star
        "M54,27 L77,40.5 L77,67.5 L54,81 L31,67.5 L31,40.5 Z",                               // hexagon
        "M33,33 H75 V75 H33 Z M45,45 V63 H63 V45 Z",                                         // framed square
        "M54,27 L80,77 H28 Z",                                                               // triangle
        "M30,54 A24,24 0 0 1 78,54 Z M30,60 H78 V66 H30 Z",                                  // dome + bar
        "M47,28 H61 V47 H80 V61 H61 V80 H47 V61 H28 V47 H47 Z",                              // plus
    )

    fun xml(seed: String): String {
        val h = seed.hashCode() and 0x7fffffff
        val color = colors[h % colors.size]
        val shape = shapes[(h / colors.size) % shapes.size]
        return """
            |<vector xmlns:android="http://schemas.android.com/apk/res/android"
            |    android:width="108dp" android:height="108dp"
            |    android:viewportWidth="108" android:viewportHeight="108">
            |    <path android:fillColor="#161826" android:pathData="M0,0h108v108h-108z" />
            |    <!-- Placeholder from the app name; replace with a glyph for what the app does (kit docs: App icon). -->
            |    <path android:fillColor="$color" android:fillType="evenOdd" android:pathData="$shape" />
            |</vector>
            |""".trimMargin()
    }
}
