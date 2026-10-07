// The Kiln app kit: the one runtime library every app Kiln builds links
// against. Compiled here (same Kotlin as the on-device compiler), shipped in the
// toolchain pack as compile-classpath jars + pre-dexed code + precompiled
// resources, so on-device builds only compile the app's own sources.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.kiln.kit"
    compileSdk = 37
    defaultConfig { minSdk = 30 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    // Everything here is `api`: generated apps compile against all of it.
    api(platform("androidx.compose:compose-bom:2026.09.00"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.ui:ui-graphics")
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.animation:animation")
    api("androidx.compose.material3:material3")
    api("androidx.compose.material:material-icons-extended")
    api("androidx.compose.material3.adaptive:adaptive")
    api("androidx.compose.material3.adaptive:adaptive-layout")
    api("androidx.compose.material3.adaptive:adaptive-navigation")
    api("androidx.compose.material3.adaptive:adaptive-navigation3")
    api("androidx.compose.material3:material3-adaptive-navigation-suite")
    api("androidx.navigation3:navigation3-runtime:1.2.0")
    api("androidx.navigation3:navigation3-ui:1.2.0")
    api("androidx.lifecycle:lifecycle-viewmodel-navigation3:2.11.0")
    api("androidx.activity:activity-compose:1.13.0")
    api("androidx.core:core-ktx:1.19.1")
    api("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    api("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    api("androidx.datastore:datastore-preferences:1.2.1")
    api("androidx.work:work-runtime-ktx:2.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    api("com.squareup.okhttp3:okhttp:5.5.0")
    api("io.coil-kt.coil3:coil-compose:3.6.3")
    api("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    // In-app purchases (KBilling). Its permission and components are merged only into apps that opt in.
    api("com.android.billingclient:billing-ktx:8.0.0")
}

// Everything the pack builder needs, resolved by Gradle: the kit's own AAR plus
// every runtime dependency (AARs and jars), flattened into one directory and
// named by coordinates (several artifacts share a file name such as ui.aar).
tasks.register("exportKit") {
    dependsOn("assembleRelease")
    val out = layout.buildDirectory.dir("kit-export")
    val kitAar = layout.buildDirectory.file("outputs/aar/kit-release.aar")
    val artifactType = org.gradle.api.attributes.Attribute.of("artifactType", String::class.java)
    val views = listOf("aar", "jar").map { type ->
        configurations.named("releaseRuntimeClasspath").get().incoming.artifactView {
            lenient(true)
            attributes.attribute(artifactType, type)
        }.artifacts
    }
    doLast {
        val dir = out.get().asFile
        dir.deleteRecursively(); dir.mkdirs()
        kitAar.get().asFile.copyTo(dir.resolve("app.kiln__kit__local.aar"))
        val lines = mutableListOf<String>()
        for (view in views) for (a in view) {
            val id = a.id.componentIdentifier
            val coord = (id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)
                ?.let { "${it.group}__${it.module}__${it.version}" } ?: a.file.nameWithoutExtension
            val name = "$coord.${a.file.extension}"
            if (!dir.resolve(name).exists()) { a.file.copyTo(dir.resolve(name)); lines += name }
        }
        dir.resolve("index.txt").writeText(lines.sorted().joinToString("\n"))
        println("exported ${lines.size + 1} artifacts")
    }
}
