import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// :ui where there is no Android SDK: the same sources and resources, compiled for
// the browser alone.
//
// build.gradle.kts beside this is the whole module -- Android, the JVM and Wasm --
// and needs an SDK twice over: the Android library plugin is published on
// Google's Maven, and so are the androidx artifacts that Compose Multiplatform's
// Android and desktop builds resolve to. Its Wasm build resolves entirely from
// Maven Central. So settings.gradle.kts picks this file where there is no SDK,
// and `./gradlew test` compiles every screen for the browser on any machine,
// which is what makes a screen that only builds on the JVM -- a java.* import, an
// Android API -- fail in seconds instead of in CI.
//
// Two build files rather than one with conditions in it, because the condition
// cannot be written: the plugins block resolves every plugin it names before any
// code in the file runs, `apply false` included, so the Android plugin cannot be
// mentioned at all where Google's Maven cannot be reached. What the two files
// share -- the plugins and versions, the common dependencies, and the resource
// settings -- is held equal by UiBuildFilesTest in :presentation, so a dependency
// added to one and not the other fails `./gradlew test` rather than surfacing as
// a browser build that drifted.
//
// The jvmMain, jvmTest and androidMain sources are simply not compiled here; the
// Wasm actuals in wasmJsMain are.
plugins {
    kotlin("multiplatform") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
    id("org.jetbrains.compose") version "1.7.3"
}

repositories {
    mavenCentral()
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs()

    sourceSets {
        commonMain.dependencies {
            api(project(":presentation"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.animation)
            implementation(compose.material3)
            implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
            implementation("io.coil-kt.coil3:coil-compose:3.0.4")
            api(compose.components.resources)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.wardrobapp.ui.resources"
    generateResClass = always
}
