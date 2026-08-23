import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Compose app. The only module that needs the Android SDK, which is why
// settings.gradle.kts includes it conditionally.
//
// It should stay thin. Filtering, ordering, form rules, the algorithms and every
// database statement live in :presentation, :domain and :data, all of which are
// plain Kotlin and tested without an emulator. What belongs here is layout,
// navigation and the platform plumbing that genuinely needs Android.
plugins {
    id("com.android.application") version "8.9.1"
    kotlin("android") version "2.1.20"
    // Kotlin 2.x ships the Compose compiler as a plugin rather than a separate
    // artifact pinned to a Kotlin version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
}

android {
    namespace = "com.wardrobapp.app"
    // Matches what the React Native app is built against, so nothing about the
    // platform surface changes under the port.
    compileSdk = 36

    defaultConfig {
        // Deliberately NOT com.anonymous.wardrobapp. Sharing that id would let
        // this read an existing wardrobe -- same data directory -- but it would
        // also replace the installed app, handing a real wardrobe to an
        // unfinished one. Until parity, this installs alongside and is loaded by
        // restoring a backup. One line changes at cutover.
        applicationId = "com.anonymous.wardrobapp.dev"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-port"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":presentation"))

    implementation("androidx.core:core-ktx:1.15.0")
    // Used directly -- Dispatchers.IO, MutableStateFlow.update -- rather than
    // relied on transitively through lifecycle-viewmodel-ktx.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // The back stack, so system back behaves the way it does everywhere else on
    // the phone rather than being reimplemented per screen.
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    // Used directly by the analytics bars, so depended on directly rather than
    // reached through whatever material3 happens to expose.
    implementation("androidx.compose.animation:animation-core")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Photos are files on disk; Coil loads them without hand-rolled decoding.
    implementation("io.coil-kt:coil-compose:2.7.0")

    // The SqlDriver implementation wraps this rather than android.database
    // directly, so WAL and the pragmas are configured the same way expo-sqlite
    // configures them.
    implementation("androidx.sqlite:sqlite-framework:2.4.0")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")
}
