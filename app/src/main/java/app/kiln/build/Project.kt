package app.kiln.build

import app.kiln.core.KJ
import kotlinx.serialization.Serializable
import java.io.File

/** kiln.json — what makes a directory a Kiln project. */
@Serializable
data class ProjectMeta(
    val `package`: String,
    val label: String,
    val versionCode: Int = 1,
    val versionName: String = "1.0",
    val minSdk: Int = 30,
    val targetSdk: Int = 36,
    val permissions: List<String> = emptyList(),
)

/**
 * A project directory. Every path the agent passes is resolved through
 * [resolve], which refuses anything outside the project root.
 */
class Project(dir: File) {
    /** Canonical: filesDir is reachable as both /data/user/0/… and /data/data/…, and
     *  every path comparison below must use one spelling. */
    val dir: File = dir.canonicalFile
    val name: String get() = dir.name
    val metaFile = File(dir, "kiln.json")
    val manifest = File(dir, "AndroidManifest.xml")
    val src = File(dir, "src")
    val res = File(dir, "res")
    val assets = File(dir, "assets")
    val kilnDir = File(dir, ".kiln")
    val buildDir = File(dir, "build")
    val memoryFile = File(kilnDir, "memory.md")
    val configFile = File(kilnDir, "config.json")

    fun meta(): ProjectMeta = KJ.decodeFromString(ProjectMeta.serializer(), metaFile.readText())

    /**
     * [path] as the project sees it. Models often write the project's own name first ("expense3/src/…"): when
     * that doesn't exist as written, it means the same path from the root (five reads failed in a row on it).
     */
    fun normalize(path: String): String {
        val p = path.trim().replace('\\', '/').removePrefix("./").removePrefix("/")
        val own = dir.name + "/"
        // Only when the project has no folder of its own name (then the prefix can't mean anything else).
        if (File(dir, dir.name).exists()) return p
        return if (p.startsWith(own)) p.removePrefix(own) else if (p == dir.name) "" else p
    }

    fun resolve(path: String): File {
        val p = normalize(path)
        val f = File(dir, p).canonicalFile
        require(f.path == dir.path || f.path.startsWith(dir.path + File.separator)) {
            "path escapes the project: $path"
        }
        return f
    }

    /** Like [resolve], for writes: `.kiln/` (signing key, settings, command tools) belongs to Kiln, not the agent. */
    fun resolveWritable(path: String): File {
        val f = resolve(path)
        val r = rel(f)
        require(r != ".kiln" && !r.startsWith(".kiln/")) { ".kiln/ is managed by Kiln (signing key, settings, tools) and can't be changed by the agent" }
        require(r != "build" && !r.startsWith("build/")) { "build/ is build output (it holds the app's secret values): edit the sources, then build" }
        return f
    }

    fun rel(f: File): String = f.canonicalFile.relativeTo(dir).invariantSeparatorsPath

    /** Source files the agent should see (skips build output and Kiln's own state). */
    fun files(): List<File> = dir.walkTopDown()
        // Only the top-level build/ and .kiln/: a source package named "build" is source.
        .onEnter { it == dir || it.parentFile != dir || (it.name != "build" && it.name != ".kiln") }
        .filter { it.isFile }.toList()

    companion object {
        val NAME = Regex("^[a-z][a-z0-9_]{1,40}$")

        fun packageFor(name: String) = "kiln.app.$name"

        /** New project from a pack template, with {{package}}/{{label}} filled in. */
        fun create(root: File, name: String, label: String, template: File): Project {
            require(NAME.matches(name)) { "project name must be lowercase letters, digits, _ (2–41 chars)" }
            val dir = File(root, name)
            require(!dir.exists()) { "project $name already exists" }
            val pkg = packageFor(name)
            template.walkTopDown().filter { it.isFile }.forEach { f ->
                var rel = f.relativeTo(template).invariantSeparatorsPath
                if (rel.startsWith("src/")) rel = "src/" + pkg.replace('.', '/') + "/" + rel.removePrefix("src/")
                val out = File(dir, rel)
                out.parentFile?.mkdirs()
                if (f.extension in setOf("kt", "xml", "json", "md", "txt"))
                    out.writeText(f.readText().replace("{{package}}", pkg).replace("{{label}}", label))
                else f.copyTo(out)
            }
            File(dir, ".kiln").mkdirs()
            File(dir, "res/drawable/ic_launcher.xml").takeIf { it.isFile }?.writeText(DefaultIcon.xml(name))
            return Project(dir)
        }

        /**
         * A copy of [from] under a new name and package (a remix): sources move to the new package
         * directory and every mention of the old package is rewritten. Build output, chats and the
         * signing key stay behind, so the copy installs side by side with the original.
         */
        /**
         * Copy [from]'s files into [dir] under package [pkg]: sources move to the new package
         * directory and every mention of the old package is rewritten. Build output and .kiln stay.
         */
        fun copySources(from: Project, dir: File, pkg: String) {
            val oldPkg = from.meta().`package`
            val oldPath = "src/" + oldPkg.replace('.', '/') + "/"
            val text = setOf("kt", "xml", "json", "md", "txt", "pro")
            for (f in from.files()) {
                var rel = from.rel(f)
                if (rel.startsWith(oldPath)) rel = "src/" + pkg.replace('.', '/') + "/" + rel.removePrefix(oldPath)
                val out = File(dir, rel)
                out.parentFile?.mkdirs()
                if (f.extension in text) out.writeText(f.readText().replace(oldPkg, pkg)) else f.copyTo(out, overwrite = true)
            }
        }

        fun duplicate(root: File, from: Project, name: String, label: String): Project {
            require(NAME.matches(name)) { "project name must be lowercase letters, digits, _ (2–41 chars)" }
            val dir = File(root, name)
            require(!dir.exists()) { "project $name already exists" }
            copySources(from, dir, packageFor(name))
            val p = Project(dir)
            p.kilnDir.mkdirs()
            from.memoryFile.takeIf { it.isFile }?.copyTo(p.memoryFile)
            p.metaFile.writeText(app.kiln.core.KJPretty.encodeToString(ProjectMeta.serializer(), p.meta().copy(label = label, versionCode = 1)))
            return p
        }
    }
}
