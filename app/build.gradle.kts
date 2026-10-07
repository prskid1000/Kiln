import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.kiln"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.kiln"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        // One key for debug and release, so builds install over each other and
        // Warden's grant (bound to the signing cert) survives.
        signingConfigs.findByName("release")?.let { rel ->
            debug { signingConfig = rel }
            release { signingConfig = rel }
        }
        release { isMinifyEnabled = false }
    }
    buildFeatures { compose = true; aidl = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST")
    }
    // The toolchain components are already compressed (zip): store them, never compress twice.
    androidResources { noCompress += listOf("jar", "zip", "bin", "kpk") }

    // The toolchain ships inside the APK, so there's one build per ABI: its JDK, aapt2 and
    // launcher are native. arm64 for phones, x86_64 for emulators and Chromebooks.
    flavorDimensions += "abi"
    productFlavors {
        create("arm64") { dimension = "abi"; isDefault = true }
        create("x86_64") { dimension = "abi" }
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation3:navigation3-runtime:1.2.0")
    implementation("androidx.navigation3:navigation3-ui:1.2.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Model connection: the official SDK for Claude; plain OkHttp + SSE for the
    // OpenAI-shaped protocols (OpenRouter, local proxies, OpenAI).
    implementation("com.anthropic:anthropic-java:2.68.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.squareup.okhttp3:okhttp-sse:5.5.0")

    // Per-project APK signing keys (self-signed X.509 into PKCS#12).
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

/**
 * Copies one ABI's toolchain components (toolchain/build_pack.py writes them to toolchain/out/components and components-x86_64)
 * into the APK under assets/toolchain. Kiln installs or updates each on launch when its
 * content hash changes.
 */
abstract class BundleToolchain : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NAME_ONLY) abstract val components: ConfigurableFileCollection
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction fun copy() {
        val out = output.get().asFile.resolve("toolchain")
        out.deleteRecursively(); out.mkdirs()
        val files = components.files.filter { it.extension == "kpk" }
        if (files.isEmpty()) logger.warn("Kiln: no toolchain components bundled — run toolchain/build_pack.py; this APK can't build apps.")
        files.forEach { it.copyTo(out.resolve(it.name)) }
    }
}

androidComponents {
    onVariants { variant ->
        val abi = variant.productFlavors.first { it.first == "abi" }.second
        val dir = rootProject.file("toolchain/out/" + if (abi == "x86_64") "components-x86_64" else "components")
        val task = tasks.register<BundleToolchain>("bundleToolchain" + variant.name.replaceFirstChar { it.uppercase() }) {
            components.from(fileTree(dir) { include("*.kpk") })
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, BundleToolchain::output)
    }
}
