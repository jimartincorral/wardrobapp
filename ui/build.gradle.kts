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
// This file is used only where there is an Android SDK. Elsewhere
// settings.gradle.kts builds this module from build.wasm.gradle.kts instead,
// which compiles the same sources for the browser alone and says why; what the
// two share is held equal by UiBuildFilesTest, so a dependency added here must
// be added there too.
//
// Compose Multiplatform 1.7, deliberately one line behind the newest. It is the
// line built on Jetpack Compose 1.7, which is what :app's BOM already pins, so
// adding this module does not quietly move the Android app to a newer Compose
// with whatever changed in it. Moving to 1.8 is its own change, to be made and
// checked on purpose.
//
// The version itself is in the root build file, with every other plugin's;
// see there for why no module names one.
plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
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
            implementation(compose.animation)
            implementation(compose.material3)
            // The handful of stock icons the bottom bar and cards use. Core, not
            // extended, for the reason Glyphs.kt gives: the extended set is every
            // icon Google has drawn, to hand the app a few.
            implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
            // Photos are files on disk; Coil loads them without hand-rolled
            // decoding. Coil 3, the multiplatform one -- Coil 2 is Android's, and
            // the screens that draw photos are moving here.
            //
            // 3.0.4 because it is the newest built on Compose Multiplatform 1.7:
            // 3.2 is built on 1.8, which would pull Jetpack Compose 1.8 into the
            // app the way the comment at the top of this file refuses to.
            //
            // No network module. Every photo the app draws is a local file -- an
            // imported image is downloaded by :net, through the address checks
            // in ImportHttp, before anything shows it -- and leaving Coil without
            // a network means a remote URL that reached a screen would draw
            // nothing rather than be fetched by a client those checks never see.
            // The browser will need one, for the server's photos, and gets it
            // with that.
            implementation("io.coil-kt.coil3:coil-compose:3.0.4")
            // The screens' strings. `api` because Res is public and :app's own
            // composables -- the ones that stay Android-only -- read it too.
            api(compose.components.resources)
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

    // This module is linted from :app, which checks its dependencies -- see the
    // lint block there for everything else. What has to be said here as well is
    // which checks are informational, because NewerVersionAvailable is off by
    // default and :app's configuration is what turns it on: a check one module
    // enables and another does not is a configuration lint refuses outright
    // (CannotEnableHidden), which is how CI 329 failed. The same three, for the
    // same reason as there.
    lint {
        informational += setOf(
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "NewerVersionAvailable",
        )
    }
}

compose.resources {
    // Public, and in a package of its own, so :app's composables can read the
    // same strings while the screens move: everything a screen shows is defined
    // here, once, and :app's res/values keeps only what Android reads itself.
    publicResClass = true
    packageOfResClass = "com.wardrobapp.ui.resources"
    generateResClass = always
}
