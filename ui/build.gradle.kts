import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The screens, as Compose Multiplatform.
//
// They are moving here from :app, which is where they were written, so that the
// browser version can draw the same ones. Three targets:
//
//  - Android, which is what :app consumes, and which is Jetpack Compose itself:
//    Compose Multiplatform's Android artifacts are androidx's.
//  - The JVM, which nothing ships. It exists so the screens can be tested with
//    Compose's own test runner on a plain JVM -- no emulator and no Robolectric --
//    the way the logic modules are tested.
//  - Wasm, for the browser.
//
// Included only where there is an Android SDK; settings.gradle.kts says why.
//
// Compose Multiplatform 1.7, deliberately one line behind the newest. It is the
// line built on Jetpack Compose 1.7, which is what :app's BOM already pins, so
// adding this module does not quietly move the Android app to a newer Compose
// with whatever changed in it. Moving to 1.8 is its own change, to be made and
// checked on purpose.
plugins {
    kotlin("multiplatform") version "2.1.20"
    id("com.android.library") version "8.9.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
    id("org.jetbrains.compose") version "1.7.3"
}

repositories {
    mavenCentral()
    google()
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs()

    sourceSets {
        commonMain.dependencies {
            api(project(":presentation"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            // Skia for the machine the tests run on, which is what lets Compose
            // build and draw anything on a plain JVM.
            implementation(compose.desktop.currentOs)
        }
    }
}

android {
    namespace = "com.wardrobapp.ui"
    // As :app's, so the two agree about the platform they build against.
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
