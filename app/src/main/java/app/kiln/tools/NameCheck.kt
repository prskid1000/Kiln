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
                // The project's own class imported from the kit's package (import app.kiln.kit.Expense): its real package.
                if (name in declared) {
                    val realFq = projectFq(project, name)
                    if (realFq != null) {
                        text = replaceImport(text, fq, realFq); fixed += "import $fq → $realFq ($name is this project's, not the kit's)"
                        corrections += Correction(fq, realFq, import = true)
                    }
                    continue
                }
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
                    // An import of a name that doesn't exist and isn't used (import app.kiln.kit.KText, …Kt): dropping it
                    // is certain. Reported as missing, the model looked for it in its code, called the errors "phantom".
                    is Fix.None -> if (!Regex("""(?<![\w.$])${Regex.escape(name)}\b""").containsMatchIn(codeOnly(text).replace(IMPORT, ""))) {
                        text = importLine(fq).replace(text, "")
                        fixed += "removed import $fq (no such name, and the file doesn't use it)"
                    } else problems += unknown(name, lineOf(original, m.range.first), s.closest)
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
        // Another package's wildcard (kotlinx.serialization.*, kotlin.reflect.*) may supply K-names (KSerializer, KClass):
        // then nothing unknown is reported, only certain fixes are made.
        val otherWildcard = Regex("""(?m)^import\s+(?!app\.kiln\.kit\.)[\w.]+\.\*""").containsMatchIn(text)
        val missing = sortedSetOf<String>()
        for (n in USE.findAll(code).map { it.value }.toSortedSet()) {
            if (n in renamed || n in declared) continue
            if (n in kit) { if (!wildcard && n !in imported) missing += n; continue }
            if (n in imported) continue   // imported from somewhere (checked above)
            when (val s = kitFix(n, kit, declared)) {
                is Fix.Exact -> {}
                // Renamed only when it's a garbled "Kiln" (KolnScreen): another K-name a letter or two off a kit name may be
                // the project's own, in a file not written yet (KidsTheme is not KilnTheme).
                is Fix.Typo -> if (GARBLED_KILN.matches(n)) {
                    text = rename(text, n, s.to); renamed += n; if (!wildcard && s.to !in imported) missing += s.to
                    fixed += "$n → ${s.to} (the kit's name; every use)"; corrections += Correction(n, s.to, import = false)
                } else if (!otherWildcard) problems += unknown(n, lineOf(text, code.indexOf(n).coerceAtLeast(0)), listOf(s.to))
                is Fix.None -> if (KIT_SHAPED.matches(n) && !otherWildcard) problems += unknown(n, lineOf(text, code.indexOf(n).coerceAtLeast(0)), s.closest)
            }
        }
        // Lower-case kit functions called without their import (rememberKToast(), rememberPref(…)).
        if (!wildcard) for (m in LOWER_CALL.findAll(code)) {
            val n = m.groupValues[1]
            if (n in kit && n !in imported && n !in declared) missing += n
        }
        for (n in missing) { text = addImport(text, "app.kiln.kit.$n"); fixed += "added import app.kiln.kit.$n" }

        // 3. Material icons: whether Icons.Filled.X exists is settled now, not left to a build (a run went back and forth
        // four times over Icons.Filled.Circle).
        val iconCode = codeOnly(text)
        for (m in ICON.findAll(iconCode).distinctBy { it.value }) {
            val (mirrored, style0, name) = m.destructured
            val style = if (style0 == "Default") "Filled" else style0   // Icons.Default is Icons.Filled
            val pkg = "androidx.compose.material.icons." + (if (mirrored.isNotEmpty()) "automirrored." else "") + style.lowercase()
            if (index.exists("$pkg.$name") || name in declared) continue   // a real icon, or one the project defines
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

    /** Every name the project declares: types, functions, properties, parameters, lambda parameters, enum entries. */
    private fun declarations(project: Project, file: File, text: String): Set<String> {
        val out = HashSet<String>()
        val sources = runCatching { project.files().filter { it.extension == "kt" && it.canonicalFile != file.canonicalFile }.map { it.readText() } }.getOrDefault(emptyList())
        for (src0 in sources + text) {
            val src = codeOnly(src0)
            DECL.findAll(src).forEach { out += it.groupValues[1] }
            PARAM.findAll(src).forEach { out += it.groupValues[1] }          // fun f(loader: …), class C(val x: …)
            LAMBDA.findAll(src).forEach { m -> m.groupValues[1].split(',').map { it.trim().substringBefore(':').trim() }
                .filter { it.matches(Regex("""[A-Za-z_]\w*""")) }.forEach { out += it } }   // { a, b -> … }
            ENUM.findAll(src).forEach { m -> m.groupValues[1].substringBefore(';').split(',')
                .map { it.trim().substringBefore('(').substringBefore('{').trim() }.filter { it.matches(Regex("""[A-Za-z_]\w*""")) }.forEach { out += it } }
        }
        return out
    }

    /** The fully-qualified name of a class/object/function the project declares at top level, from its file's package. */
    private fun projectFq(project: Project, name: String): String? = runCatching {
        project.files().filter { it.extension == "kt" }.firstNotNullOfOrNull { f ->
            val t = f.readText()
            if (!Regex("""(?m)^(?:[\w@]+\s+)*(?:class|object|interface|typealias|fun)\s+(?:<[^>]*>\s*)?${Regex.escape(name)}\b""").containsMatchIn(t)) null
            else Regex("""(?m)^package\s+([\w.]+)""").find(t)?.groupValues?.get(1)?.let { "$it.$name" }
        }
    }.getOrNull()

    internal companion object {
        val IMPORT = Regex("""(?m)^import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$""")
        // Kit-style names (KCardBox, KilnScreen), plus a garbled Kiln (KolnScreen): those are only ever typo-fixed,
        // never reported, so ordinary names of that shape (KeyEvent) are left alone.
        val USE = Regex("""(?<![\w.$])(?:K[A-Z]\w*|Kiln[A-Z]\w*|K[a-z]{2,4}[A-Z]\w*)\b""")
        val KIT_SHAPED = Regex("""K[A-Z]\w*|Kiln[A-Z]\w*""")
        val ICON = Regex("""(?<![\w.])Icons\.(AutoMirrored\.)?(Filled|Default|Outlined|Rounded|Sharp|TwoTone)\.([A-Z]\w*)""")
        /** "Kiln" with one letter garbled (KolnScreen, KilmTabs…): the only K-names renamed without asking. */
        val GARBLED_KILN = Regex("""K(?:[a-z]ln|i[a-z]n|il[a-z])[A-Z]\w*""")
        val PARAM = Regex("""[(,]\s*(?:@\w+\s+)*(?:(?:private|internal|override|open|vararg|crossinline|noinline)\s+)*(?:val\s+|var\s+)?([A-Za-z_]\w*)\s*:""")
        val LAMBDA = Regex("""\{\s*((?:[A-Za-z_]\w*(?:\s*:\s*[\w.<>?]+)?\s*,\s*)*[A-Za-z_]\w*(?:\s*:\s*[\w.<>?]+)?)\s*->""")
        val ENUM = Regex("""\benum\s+class\s+\w+[^{]*\{([^}]*)""")
        /** A bare lower-case call (not a member: no "." before it). */
        val LOWER_CALL = Regex("""(?<![\w.$])([a-z]\w*)\s*[({]""")
        val DECL = Regex("""\b(?:class|object|interface|typealias|fun|val|var)\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?([A-Za-z_]\w*)""")
        val WORDS = Regex("""[A-Z][a-z0-9]+|[a-z0-9]+""")
        val CHECKED = listOf("androidx.", "kotlinx.", "android.", "java.", "javax.", "com.google.")

        fun lineOf(text: String, at: Int) = text.substring(0, at.coerceIn(0, text.length)).count { it == '\n' } + 1

        /**
         * The whole `import [from]` line (with any `as` alias) → `import [to]` (alias kept), or dropped when [to] is
         * already imported. Anchored at the line's end: `…LocalDate` must not match the start of `…LocalDateTime`.
         */
        fun replaceImport(text: String, from: String, to: String): String {
            val has = Regex("""(?m)^import\s+${Regex.escape(to)}[ \t]*$""").containsMatchIn(text)
            return importLine(from).replace(text) { m ->
                val alias = m.groupValues[1]
                if (has && alias.isEmpty()) "" else "import $to$alias\n" }
        }

        /** Matches the whole line importing exactly [fq] (alias as group 1), with its line break. */
        fun importLine(fq: String) = Regex("""(?m)^import\s+${Regex.escape(fq)}(\s+as\s+\w+)?[ \t]*(?:\r?\n|$)""")

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
                    // A raw string spans lines: all of it is text (only its line breaks stay, so line numbers hold).
                    b.startsWith("\"\"\"", i) -> { val end = b.indexOf("\"\"\"", i + 3).let { if (it < 0) b.length else it + 3 }
                        i += 3; while (i < end - 3) { if (b[i] != '\n') b.setCharAt(i, ' '); i++ }; i = end }
                    b[i] == '"' -> { i++; while (i < b.length && b[i] != '"' && b[i] != '\n') { if (b[i] == '\\') { b.setCharAt(i, ' '); i++ }
                        if (i < b.length) { b.setCharAt(i, ' '); i++ } }; i++ }
                    // A char literal ('(', '\n', '"'): blanked, so it can't open a string or unbalance parentheses.
                    b[i] == '\'' -> { val end = (i + 1 until minOf(b.length, i + 8)).firstOrNull { j -> b[j] == '\'' && b[j - 1] != '\\' || (b[j] == '\'' && j >= 2 && b[j - 1] == '\\' && b[j - 2] == '\\') }
                        if (end == null) i++ else { for (j in i + 1 until end) b.setCharAt(j, ' '); i = end + 1 } }
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
