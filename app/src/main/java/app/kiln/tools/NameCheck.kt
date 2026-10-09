package app.kiln.tools

import app.kiln.build.Project
import app.kiln.toolchain.Toolchain
import java.io.File

/**
 * Checks the names a Kotlin file uses against the kit and the classpath right after it is written, so a
 * made-up or garbled name is caught at once instead of in a build with dozens of errors (runs: KolnScreen,
 * app.kiln.kit.Koln.kit.rememberKToast, KSlide — each cost a full rewrite of a 7 KB screen).
 *
 * What is certain is fixed in place (a kit name under a garbled package, a one- or two-letter typo of a kit
 * name, a kit import that's missing, an import from a package that doesn't exist whose class is known);
 * the rest is reported with the closest real names and their signatures.
 */
class NameCheck(private val index: ClassIndex, private val toolchain: Toolchain) {

    /** The file's text after the certain fixes, and what to tell the model (empty when all is well). */
    data class Result(val text: String, val report: String, val corrections: List<Correction> = emptyList())

    /** A certain fix: an import path ([import] true) or a name used in code. Applied to other files the same way. */
    data class Correction(val from: String, val to: String, val import: Boolean) {
        fun applyTo(text: String): String = if (import) replaceImport(text, from, to)
            else rename(replaceImport(text, "app.kiln.kit.$from", "app.kiln.kit.$to"), from, to)
        override fun toString() = "$to (not $from)"
    }

    fun check(project: Project, file: File, original: String): Result? {
        if (file.extension != "kt") return null
        var text = original
        val fixed = mutableListOf<String>()
        val problems = mutableListOf<String>()
        val kit = index.kitNames()
        val own = Regex("""(?m)^package\s+([\w.]+)""").find(text)?.groupValues?.get(1).orEmpty().split('.').take(3).joinToString(".")
        val declared = declarations(project, file, text)
        val renamed = HashSet<String>()
        val corrections = mutableListOf<Correction>()

        // 1. Imports.
        for (m in IMPORT.findAll(original).toList()) {
            val fq = m.groupValues[1]
            if (own.isNotEmpty() && fq.startsWith("$own.")) continue
            val name = fq.substringAfterLast('.')
            val pkg = fq.substringBeforeLast('.', "")
            if (fq.startsWith("app.kiln.kit.")) {
                if (index.exists(fq) || index.exists(pkg) || (pkg == "app.kiln.kit" && name in kit)) continue   // a kit symbol, or a member of a kit object
                // Not a kit name but a real class elsewhere (import app.kiln.kit.Icons): the import points there.
                val real = if (name !in kit) runCatching { index.uniqueClass(name) }.getOrNull()?.takeIf { !it.startsWith("app.kiln.kit.") } else null
                if (real != null) {
                    text = replaceImport(text, fq, real); fixed += "import $fq → $real ($name isn't from the kit)"
                    corrections += Correction(fq, real, import = true); continue
                }
                when (val s = kitFix(name, kit, declared)) {
                    is Fix.Exact -> { text = replaceImport(text, fq, "app.kiln.kit.$name"); fixed += "import $fq → app.kiln.kit.$name"
                        corrections += Correction(fq, "app.kiln.kit.$name", import = true) }
                    is Fix.Typo -> { text = rename(replaceImport(text, fq, "app.kiln.kit.${s.to}"), name, s.to); renamed += name
                        fixed += "$name → ${s.to} (the kit's name; import and every use)"; corrections += Correction(name, s.to, import = false) }
                    is Fix.None -> problems += unknown(name, lineOf(original, m.range.first), s.closest)
                }
            } else if (CHECKED.any { fq.startsWith(it) } && !index.exists(fq) && !index.exists(pkg) && !index.packageExists(pkg)) {
                val alt = runCatching { index.uniqueClass(name) }.getOrNull()
                if (alt != null && alt != fq) { text = replaceImport(text, fq, alt); fixed += "import $fq → $alt (package $pkg isn't on this classpath)"
                    corrections += Correction(fq, alt, import = true) }
                else problems += "line ${lineOf(original, m.range.first)}: package $pkg isn't on this classpath (only the kit and the libraries in its reference exist) — " +
                    "remove `import $fq` and use a bundled alternative (kit_search \"$name\")."
            }
        }

        // 2. Kit-style names used in the code (K + capital, Kiln + capital).
        val code = codeOnly(text)
        val imported = IMPORT.findAll(text).map { it.groupValues[2].ifEmpty { it.groupValues[1].substringAfterLast('.') } }.toSet()
        val wildcard = Regex("""(?m)^import\s+app\.kiln\.kit\.\*""").containsMatchIn(text)
        val missing = sortedSetOf<String>()
        for (n in USE.findAll(code).map { it.value }.toSortedSet()) {
            if (n in renamed || n in declared) continue
            if (n in kit) { if (!wildcard && n !in imported) missing += n; continue }
            if (n in imported) continue   // imported from somewhere (checked above)
            when (val s = kitFix(n, kit, declared)) {
                is Fix.Exact -> {}
                is Fix.Typo -> { text = rename(text, n, s.to); renamed += n; if (!wildcard && s.to !in imported) missing += s.to
                    fixed += "$n → ${s.to} (the kit's name; every use)"; corrections += Correction(n, s.to, import = false) }
                is Fix.None -> if (KIT_SHAPED.matches(n)) problems += unknown(n, lineOf(text, code.indexOf(n).coerceAtLeast(0)), s.closest)
            }
        }
        for (n in missing) { text = addImport(text, "app.kiln.kit.$n"); fixed += "added import app.kiln.kit.$n" }

        // 3. Material icons: whether Icons.Filled.X exists is settled now, not left to a build (a run went back and forth
        // four times over Icons.Filled.Circle).
        val iconCode = codeOnly(text)
        for (m in ICON.findAll(iconCode).distinctBy { it.value }) {
            val (mirrored, style, name) = m.destructured
            val pkg = "androidx.compose.material.icons." + (if (mirrored.isNotEmpty()) "automirrored." else "") + style.lowercase()
            if (index.exists("$pkg.$name")) continue
            val names = runCatching { index.iconNames(pkg) }.getOrDefault(emptyList())
            if (names.isEmpty()) continue   // icons not on this classpath: nothing to compare with
            val mapped = MATERIAL_ICON_NAMES[name.lowercase()]?.takeIf { it in names }
            val near = (listOfNotNull(mapped) + names.map { it to distance(name.lowercase(), it.lowercase()) }
                .filter { it.second <= maxOf(2, name.length / 3) }.sortedBy { it.second }.map { it.first }).distinct().take(4)
            problems += "line ${lineOf(text, m.range.first)}: ${m.value} isn't a Material icon" +
                (if (near.isEmpty()) " — pick another (Icons.${if (mirrored.isNotEmpty()) "AutoMirrored." else ""}$style has Home, Settings, Add, Delete, Edit, Search, Star, Info…)."
                 else ". Closest: " + near.joinToString { "Icons.${if (mirrored.isNotEmpty()) "AutoMirrored." else ""}$style.$it" })
        }

        if (fixed.isEmpty() && problems.isEmpty()) return Result(text, "")
        val out = StringBuilder("\nKiln checked this file's names against the kit and classpath:\n")
        if (fixed.isNotEmpty()) {
            out.append("Fixed in the file (certain):\n")
            fixed.forEach { out.append("  ✓ ").append(it).append('\n') }
        }
        if (problems.isNotEmpty()) {
            out.append("Still to fix — these will fail the build:\n")
            problems.forEach { out.append("  ✗ ").append(it).append('\n') }
            out.append("Fix only those lines with edit_file (read_file the lines first if you need them); don't rewrite the file.\n")
        }
        if (fixed.isNotEmpty()) out.append("The fixes above are already saved: edit from the file as it is now, not from what you sent.\n")
        return Result(text, out.toString(), corrections)
    }

    /** "KSlide isn't in the kit — did you mean …" with signatures; also used for unresolved-reference build errors. */
    fun suggest(name: String): String? {
        val kit = runCatching { index.kitNames() }.getOrNull() ?: return null
        if (name in kit) return null
        val closest = closest(name, kit)
        if (closest.isEmpty()) return null
        return "$name isn't in the kit. Closest:\n" + closest.joinToString("\n") { "    ${signature(it) ?: it}" }
    }

    // ---------------------------------------------------------------- internals

    private sealed interface Fix {
        object Exact : Fix
        data class Typo(val to: String) : Fix
        data class None(val closest: List<String>) : Fix
    }

    private fun kitFix(name: String, kit: Set<String>, declared: Set<String>): Fix {
        if (name in kit) return Fix.Exact
        if (name in declared) return Fix.None(emptyList())
        val near = kit.map { it to distance(name.lowercase(), it.lowercase()) }.sortedBy { it.second }
        val best = near.firstOrNull() ?: return Fix.None(emptyList())
        // A one- or two-letter slip of exactly one kit name, same length (KolnScreen, KTextFeild): certain enough to fix.
        // A name a letter longer or shorter is often another component (KSlide meant KSwipeRow, not KSlider): only suggested.
        if (name.length >= 5 && best.first.length == name.length && best.second <= (if (name.length >= 8) 2 else 1) &&
            near.count { it.second == best.second } == 1)
            return Fix.Typo(best.first)
        return Fix.None(closest(name, kit))
    }

    /** The index's suggestions first (it knows the usual inventions: toolbar → KilnScreen, toast → rememberKToast), then spelling. */
    private fun closest(name: String, kit: Set<String>): List<String> =
        (index.similarKitNames(name, 4).map { it.substringBefore(" — ") } + spelling(name, kit)).distinct().take(4)

    private fun spelling(name: String, kit: Set<String>): List<String> {
        val low = name.lowercase()
        // Edit distance, plus shared words (KSlide ~ KSlider, KSwipeRow; KToastShow ~ KToast).
        val words = WORDS.findAll(name.removePrefix("K").removePrefix("Kiln")).map { it.value.lowercase() }.filter { it.length >= 3 }.toList()
        return kit.map { k ->
            val d = distance(low, k.lowercase())
            val shared = words.count { w -> k.lowercase().contains(w) }
            k to d - shared * 3
        }.filter { it.second <= maxOf(3, name.length / 2) }.sortedBy { it.second }.take(4).map { it.first }
    }

    private fun unknown(name: String, line: Int, closest: List<String>): String =
        // Not "invented" outright: it may be the model's own, in a file it hasn't written yet.
        "line $line: $name isn't in the kit or declared in the project (yet — fine if you're about to write it)" + (if (closest.isEmpty()) "" else ". Kit names closest to it:\n" +
            closest.joinToString("\n") { "      ${signature(it) ?: it}" }) +
            "\n    (or kit_search what you need, e.g. \"swipe to delete\")"

    private fun signature(name: String): String? =
        index.kitSignature(name)?.let { s -> if (s.length > 220) s.take(220) + "…)" else s }

    private fun declarations(project: Project, file: File, text: String): Set<String> {
        val out = HashSet<String>()
        val sources = runCatching { project.files().filter { it.extension == "kt" && it.canonicalFile != file.canonicalFile }.map { it.readText() } }.getOrDefault(emptyList())
        for (src in sources + text) DECL.findAll(src).forEach { out += it.groupValues[1] }
        return out
    }

    internal companion object {
        val IMPORT = Regex("""(?m)^import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$""")
        // Kit-style names (KCardBox, KilnScreen), plus a garbled Kiln (KolnScreen): those are only ever typo-fixed,
        // never reported, so ordinary names of that shape (KeyEvent) are left alone.
        val USE = Regex("""(?<![\w.$])(?:K[A-Z]\w*|Kiln[A-Z]\w*|K[a-z]{2,4}[A-Z]\w*)\b""")
        val KIT_SHAPED = Regex("""K[A-Z]\w*|Kiln[A-Z]\w*""")
        val ICON = Regex("""(?<![\w.])Icons\.(AutoMirrored\.)?(Filled|Outlined|Rounded|Sharp|TwoTone)\.([A-Z]\w*)""")
        val DECL = Regex("""\b(?:class|object|interface|typealias|fun|val|var)\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?([A-Za-z_]\w*)""")
        val WORDS = Regex("""[A-Z][a-z0-9]+|[a-z0-9]+""")
        val CHECKED = listOf("androidx.", "kotlinx.", "android.", "java.", "javax.", "com.google.")

        fun lineOf(text: String, at: Int) = text.substring(0, at.coerceIn(0, text.length)).count { it == '\n' } + 1

        fun replaceImport(text: String, from: String, to: String): String {
            val has = Regex("""(?m)^import\s+${Regex.escape(to)}\s*$""").containsMatchIn(text)
            return Regex("""(?m)^import\s+${Regex.escape(from)}\s*\n?""").replace(text) { if (has) "" else "import $to\n" }
        }

        /** [from] → [to] wherever it is a whole name in code (not inside strings or other words). */
        fun rename(text: String, from: String, to: String): String {
            val code = codeOnly(text)   // matches in comments and strings are left as written
            return Regex("""(?<![\w$])${Regex.escape(from)}\b""").replace(text) { m ->
                if (code.regionMatches(m.range.first, from, 0, from.length)) to else m.value }
        }

        /** The text with comments and string literals blanked (same length, so offsets still match). */
        fun codeOnly(text: String): String {
            val b = StringBuilder(text)
            var i = 0
            while (i < b.length) {
                when {
                    b.startsWith("//", i) -> { while (i < b.length && b[i] != '\n') { b.setCharAt(i, ' '); i++ } }
                    b.startsWith("/*", i) -> { val end = b.indexOf("*/", i + 2).let { if (it < 0) b.length else it + 2 }
                        while (i < end) { if (b[i] != '\n') b.setCharAt(i, ' '); i++ } }
                    b[i] == '"' -> { i++; while (i < b.length && b[i] != '"' && b[i] != '\n') { if (b[i] == '\\') { b.setCharAt(i, ' '); i++ }
                        if (i < b.length) { b.setCharAt(i, ' '); i++ } }; i++ }
                    else -> i++
                }
            }
            return b.toString()
        }

        fun distance(a: String, b: String): Int {
            val d = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = d[0]; d[0] = i
                for (j in 1..b.length) {
                    val t = d[j]
                    d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = t
                }
            }
            return d[b.length]
        }
    }
}
