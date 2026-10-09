package app.kiln.build

import kotlinx.coroutines.sync.withLock
import app.kiln.core.Exec
import app.kiln.core.ExecResult
import app.kiln.toolchain.ToolHost
import app.kiln.toolchain.Toolchain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

@Serializable
data class StepTiming(val step: String, val ms: Long, val skipped: Boolean = false)

@Serializable
data class BuildResult(
    val ok: Boolean,
    val apk: String? = null,            // absolute path of the signed APK
    val diagnostics: List<Diagnostic> = emptyList(),
    val steps: List<StepTiming> = emptyList(),
    val totalMs: Long = 0,
) {
    val errors get() = diagnostics.filter { it.severity == "error" }
    val warnings get() = diagnostics.filter { it.severity == "warning" }
}

/**
 * The on-device build: no Gradle, the same tools Gradle drives.
 *
 *   manifest merge → aapt2 compile (app res) → aapt2 link (+ kit res, stable IDs)
 *   → javac R.java → kotlinc (Compose + serialization plugins) → d8 (app classes)
 *   → package (app dex + pre-dexed kit, aligned) → apksigner
 *
 * Kit code is pre-dexed in the pack, so only the app's own classes are dexed.
 * Each step is skipped when its inputs' hash is unchanged since the last
 * successful run (build/.stamps/<step>). `check` stops after kotlinc.
 */
class BuildEngine(private val toolchain: Toolchain, private val host: ToolHost,
                  /** Secret values by name for a project (see [AppSecrets]); null when one isn't set. */
                  private val secretValues: (Project, String) -> String? = { _, _ -> null }) {

    /** One build per project at a time: the Run button, the agent and evals share build/. */
    private val locks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /** What a build is for: running here (debuggable), or shipping (APK or Play bundle). */
    enum class Kind { DEBUG, RELEASE_APK, RELEASE_AAB }

    private val running = java.util.concurrent.atomic.AtomicInteger()
    private val switching = kotlinx.coroutines.sync.Mutex()
    private object PAUSE

    /** Wait for running builds to finish and hold new ones until [resumeBuilds] (the toolchain is being swapped). */
    suspend fun pauseBuilds() { switching.lock(PAUSE); while (running.get() > 0) kotlinx.coroutines.delay(200) }
    fun resumeBuilds() { if (switching.holdsLock(PAUSE)) switching.unlock(PAUSE) }   // only the pause's own hold

    suspend fun build(project: Project, checkOnly: Boolean = false, kind: Kind = Kind.DEBUG, onStep: (String) -> Unit = {}): BuildResult {
        val lock = locks.getOrPut(project.dir.path) { kotlinx.coroutines.sync.Mutex() }
        if (lock.isLocked) onStep("waiting for the other build")
        return lock.withLock {
            // Counted, and held off while the toolchain is being swapped (its old files are deleted after).
            switching.withLock { running.incrementAndGet() }
            // A bad kiln.json, a missing toolchain or a packaging failure is a failed build, not a crash.
            try { buildLocked(project, checkOnly, kind, onStep) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                BuildResult(false, null, listOf(Diagnostic("error", "build failed: ${e.message ?: e}", tool = "kiln")))
            }
            finally { running.decrementAndGet() }
        }
    }

    private suspend fun buildLocked(project: Project, checkOnly: Boolean, kind: Kind, onStep: (String) -> Unit): BuildResult =
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val tc = toolchain.require()
            val meta = project.meta()
            val b = project.buildDir.apply { mkdirs() }
            val stamps = File(b, ".stamps").apply { mkdirs() }
            val steps = mutableListOf<StepTiming>()
            val diags = mutableListOf<Diagnostic>()

            fun fail() = BuildResult(false, null, diags, steps, System.currentTimeMillis() - t0)

            // A project's package is fixed by its name (kiln.json is editable): another project's package would install
            // over — and on a key clash uninstall — that project's app and data.
            val expected = Project.packageFor(project.name)
            if (meta.`package` != expected) {
                diags += Diagnostic("error", "kiln.json's package is ${meta.`package`}, but this project's package is $expected — set it back", tool = "kiln")
                return@withContext fail()
            }

            suspend fun step(name: String, inputs: () -> String, body: suspend () -> Boolean): Boolean {
                onStep(name)
                val s0 = System.currentTimeMillis()
                val key = hash(inputs())
                val stamp = File(stamps, name)
                if (stamp.isFile && stamp.readText() == key) { steps += StepTiming(name, 0, skipped = true); return true }
                stamp.delete()
                val ok = body()
                steps += StepTiming(name, System.currentTimeMillis() - s0)
                if (ok) stamp.writeText(key)
                return ok
            }

            // 1. manifest
            val manifest = File(b, "AndroidManifest.xml")
            val kitManifest = File(toolchain.kit(tc), "manifest.xml")
            runCatching {
                if (!step("manifest", { fingerprint(listOf(project.manifest, project.metaFile, kitManifest)) }) {
                    manifest.writeText(ManifestMerger.merge(project.manifest, kitManifest, meta)); true
                }) return@withContext fail()
            }.onFailure {
                diags += Diagnostic("error", "AndroidManifest.xml / kiln.json: ${it.message}", "AndroidManifest.xml", tool = "manifest")
                return@withContext fail()
            }

            // 2. resources
            val appRes = File(b, "app-res.zip")
            val resApk = File(b, "res.apk")
            val gen = File(b, "gen")
            val ids = File(b, "ids.txt")
            val aaptEnv = toolchain.env(tc)
            if (!step("aapt2-compile", { fingerprint(project.res.walkTopDown().filter { it.isFile }.toList()) }) {
                    if (!project.res.isDirectory) { appRes.delete(); return@step true }
                    val r = Exec.linker(toolchain.aapt2(tc), listOf("compile", "--dir", project.res.path, "-o", appRes.path), aaptEnv)
                    diags += Diagnostics.parse("aapt2", r.all, project, !r.ok); r.ok
                }) return@withContext fail()
            if (!step("aapt2-link", { fingerprint(listOf(manifest, appRes)) + tc.path + ":" + (kind == Kind.DEBUG) }) {
                    // Stable IDs make the kit's precompiled R classes valid for this app.
                    ids.writeText(File(toolchain.kit(tc), "res/ids.txt").readText()
                        .replace(Regex("^kiln\\.stub:", RegexOption.MULTILINE), "${meta.`package`}:"))
                    gen.deleteRecursively(); gen.mkdirs()
                    val args = mutableListOf("link", "-I", toolchain.androidJar(tc).path, "--manifest", manifest.path,
                        "--stable-ids", ids.path, "--auto-add-overlay", "--java", gen.path, "-o", resApk.path,
                        "--min-sdk-version", meta.minSdk.toString(), "--target-sdk-version", meta.targetSdk.toString())
                    // Debuggable while developing: Kiln's Files tab reads and edits the app's private data via
                    // run-as. Never in a release: Play rejects debuggable apps.
                    if (kind == Kind.DEBUG) args += "--debug-mode"
                    toolchain.kitResZips(tc).forEach { args += listOf("-R", it.path) }
                    if (appRes.isFile) args += appRes.path
                    val r = Exec.linker(toolchain.aapt2(tc), args, aaptEnv)
                    diags += Diagnostics.parse("aapt2", r.all, project, !r.ok); r.ok
                }) return@withContext fail()

            // 3. R classes
            val rClasses = File(b, "r-classes")
            if (!step("javac-R", { fingerprint(gen.walkTopDown().filter { it.isFile }.toList()) }) {
                    rClasses.deleteRecursively(); rClasses.mkdirs()
                    val srcs = gen.walkTopDown().filter { it.extension == "java" }.map { it.path }.toList()
                    if (srcs.isEmpty()) return@step true
                    val r = host.run("javac", listOf("--release", "17", "-nowarn", "-d", rClasses.path) + srcs)
                    diags += Diagnostics.parse("javac", r.all, project, !r.ok); r.ok
                }) return@withContext fail()

            // 4. Kotlin
            val classes = File(b, "classes")
            // The app's secrets, as a generated AppSecrets object (rewritten only when it changes).
            val secretsSrc = File(b, "gen-secrets/AppSecrets.kt")
            val secretNames = AppSecrets.names(project)
            if (secretNames.isEmpty()) secretsSrc.delete() else {
                val missing = secretNames.filter { secretValues(project, it) == null }
                if (missing.isNotEmpty()) diags += Diagnostic("warning",
                    "secrets with no value yet (empty in this build): ${missing.joinToString()} — set them in the app's Secrets", tool = "kiln")
                val text = AppSecrets.source(meta.`package`, secretNames.associateWith { secretValues(project, it) ?: "" })
                if (!secretsSrc.isFile || secretsSrc.readText() != text) { secretsSrc.parentFile?.mkdirs(); secretsSrc.writeText(text) }
            }
            val kotlinSrcs = project.src.walkTopDown().filter { it.extension == "kt" || it.extension == "java" }.toList() +
                listOfNotNull(secretsSrc.takeIf { it.isFile })
            if (kotlinSrcs.none { it.extension == "kt" }) {
                diags += Diagnostic("error", "no Kotlin sources under src/", "src", tool = "kotlinc")
                return@withContext fail()
            }
            val kc = toolchain.kotlinc(tc)
            val cp = (listOf(toolchain.androidJar(tc)) + toolchain.kitClasspath(tc) + rClasses).joinToString(":") { it.path }
            if (!step("kotlinc", { fingerprint(kotlinSrcs) + fingerprint(rClasses.walkTopDown().filter { it.isFile }.toList()) + tc.path }) {
                    classes.deleteRecursively(); classes.mkdirs()
                    val r = host.run("kotlinc", listOf(
                        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-Xjdk-release=17",
                        "-Xplugin=${File(kc, "compose-compiler-plugin.jar").path}",
                        "-Xplugin=${File(kc, "kotlinx-serialization-compiler-plugin.jar").path}",
                        "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                        "-classpath", cp, "-d", classes.path) + kotlinSrcs.map { it.path })
                    diags += Diagnostics.parse("kotlinc", r.all, project, !r.ok); r.ok
                }) return@withContext fail()
            if (checkOnly) return@withContext BuildResult(true, null, diags, steps, System.currentTimeMillis() - t0)

            // 5. dex the app's own classes
            val dexDir = File(b, "dex")
            if (!step("d8", { fingerprint((classes.walkTopDown() + rClasses.walkTopDown()).filter { it.isFile }.toList()) }) {
                    dexDir.deleteRecursively(); dexDir.mkdirs()
                    val classFiles = (classes.walkTopDown() + rClasses.walkTopDown()).filter { it.extension == "class" }.map { it.path }.toList()
                    val args = mutableListOf("--release", "--min-api", meta.minSdk.toString(),
                        "--lib", toolchain.androidJar(tc).path, "--output", dexDir.path)
                    toolchain.kitClasspath(tc).forEach { args += listOf("--classpath", it.path) }
                    val r = host.run("d8", args + classFiles)
                    diags += Diagnostics.parse("d8", r.all, project, !r.ok); r.ok
                }) return@withContext fail()

            // 6. package + sign
            val appDex = dexDir.listFiles { f -> f.extension == "dex" }!!
                .sortedWith(compareBy({ it.name.length }, { it.name }))   // classes.dex first
            val allDex = appDex.toList() + toolchain.kitDex(tc)
            val unsigned = File(b, "app-unsigned.apk")
            val release = File(b, "release").apply { if (kind != Kind.DEBUG) mkdirs() }
            val fileBase = "${meta.label.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifBlank { project.name }}-${meta.versionName.replace(Regex("[^A-Za-z0-9._]+"), "-").replace("..", "-")}"
            val signed = when (kind) {
                Kind.DEBUG -> File(b, "${meta.`package`}.apk")
                Kind.RELEASE_APK -> File(release, "$fileBase.apk")
                Kind.RELEASE_AAB -> File(release, "$fileBase.aab")
            }
            onStep("package")
            val p0 = System.currentTimeMillis()
            if (kind == Kind.RELEASE_AAB) {
                // A bundle needs resources in proto format: link again with --proto-format.
                val proto = File(b, "res-proto.apk")
                val args = mutableListOf("link", "--proto-format", "-I", toolchain.androidJar(tc).path, "--manifest", manifest.path,
                    "--stable-ids", ids.path, "--auto-add-overlay", "-o", proto.path,
                    "--min-sdk-version", meta.minSdk.toString(), "--target-sdk-version", meta.targetSdk.toString())
                toolchain.kitResZips(tc).forEach { args += listOf("-R", it.path) }
                if (appRes.isFile) args += appRes.path
                val r = Exec.linker(toolchain.aapt2(tc), args, aaptEnv)
                diags += Diagnostics.parse("aapt2", r.all, project, !r.ok)
                if (!r.ok) return@withContext fail()
                AabPackager.pack(proto, allDex, project.assets, unsigned)
            } else ApkPackager.pack(resApk, allDex, project.assets, unsigned)
            steps += StepTiming("package", System.currentTimeMillis() - p0)

            onStep("sign")
            val s0 = System.currentTimeMillis()
            val key = Signing.ensure(project)
            signed.delete()
            // A bundle carries a JAR (v1) signature only; Play re-signs the APKs it generates from it.
            val schemes = if (kind == Kind.RELEASE_AAB) listOf("--min-sdk-version", meta.minSdk.toString(),
                "--v1-signing-enabled", "true", "--v2-signing-enabled", "false", "--v3-signing-enabled", "false",
                "--v4-signing-enabled", "false") else emptyList()
            val r: ExecResult = host.run("apksigner", listOf("sign", "--ks", key.keystore.path, "--ks-type", "PKCS12",
                "--ks-pass", "pass:${key.password}", "--ks-key-alias", key.alias) + schemes + listOf("--out", signed.path, unsigned.path))
            steps += StepTiming("sign", System.currentTimeMillis() - s0)
            if (!r.ok || !signed.isFile) {
                diags += Diagnostic("error", "apksigner: ${r.all.trim().ifBlank { "failed" }}", tool = "apksigner")
                return@withContext fail()
            }
            BuildResult(true, signed.path, diags, steps, System.currentTimeMillis() - t0)
        }

    /** Forget every step stamp: the next build runs everything. */
    fun clean(project: Project) { project.buildDir.deleteRecursively() }

    private fun fingerprint(files: List<File>): String = files.sortedBy { it.path }
        .joinToString("|") { "${it.path}:${it.length()}:${it.lastModified()}" }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
