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

// Bumped past the high-water mark of anything already published if
// GITHUB_RUN_NUMBER is ever reset -- which happens if the workflow is deleted and
// recreated. A versionCode that goes backwards makes every later build refuse to
// install. Kept in step with VERSION_CODE_OFFSET in app.config.js.
val versionCodeOffset = 1000

// 0 when building locally, which is fine: a local build upgrades nothing.
val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0

// The release key, when there is one. Read as Gradle properties so CI can pass
// them as ORG_GRADLE_PROJECT_* environment variables, without any of them ever
// being written to a file in the repo.
//
// Absent locally and in the port's CI job, which is why the release build type
// below falls back to the debug key rather than failing: a contributor with no
// keystore must still be able to build.
val releaseStoreFile: String? = providers.gradleProperty("WARDROBAPP_STORE_FILE").orNull
val releaseStorePassword: String? = providers.gradleProperty("WARDROBAPP_STORE_PASSWORD").orNull
val releaseKeyAlias: String? = providers.gradleProperty("WARDROBAPP_KEY_ALIAS").orNull
val releaseKeyPassword: String? = providers.gradleProperty("WARDROBAPP_KEY_PASSWORD").orNull

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
        // The same formula app.config.js uses for the React Native app, so
        // that at cutover -- when this takes over that applicationId -- the
        // port is an upgrade of what is installed rather than a build Android
        // refuses as a downgrade. A hardcoded 1 was exactly that trap.
        //
        // The offset is duplicated rather than shared because the two builds
        // have no common configuration language; if it is ever bumped in
        // app.config.js it has to be bumped here too.
        versionCode = versionCodeOffset + ciRunNumber
        versionName = "0.1-port"
    }

    signingConfigs {
        // Declared unconditionally -- Gradle needs the name to exist for the
        // reference below to resolve -- but only populated when a keystore was
        // supplied. An unpopulated config is never selected.
        create("release") {
            if (releaseStoreFile != null) {
                storeFile = file(releaseStoreFile)
                // A keystore path with no password is a misconfigured build, not
                // the ordinary keystore-free case. Failing loudly beats signing
                // with the debug key while calling it a release.
                storePassword = releaseStorePassword
                    ?: error("WARDROBAPP_STORE_PASSWORD is required when WARDROBAPP_STORE_FILE is set.")
                keyAlias = releaseKeyAlias
                    ?: error("WARDROBAPP_KEY_ALIAS is required when WARDROBAPP_STORE_FILE is set.")
                keyPassword = releaseKeyPassword
                    ?: error("WARDROBAPP_KEY_PASSWORD is required when WARDROBAPP_STORE_FILE is set.")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Falls back to the debug key when no keystore is configured, which
            // matches what plugins/withReleaseSigning.js does to the React
            // Native app's build.gradle. Both apps are then signed by the same
            // key once the secrets exist, which is what makes cutover an
            // in-place upgrade instead of a second uninstall.
            signingConfig = if (releaseStoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    // A photo from a camera roll records which way up it is in EXIF rather than
    // in its pixels, and BitmapFactory ignores the tag -- so without this every
    // garment shot in portrait is stored lying on its side.
    implementation("androidx.exifinterface:exifinterface:1.3.7")
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

    // On-device background removal. The same model and the same version the React
    // Native app already ships (its native module is Kotlin calling this), so the
    // two produce the same cut-outs. Still a beta upstream; matching what ships is
    // the defensible choice.
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")

    // Photos are files on disk; Coil loads them without hand-rolled decoding.
    implementation("io.coil-kt:coil-compose:2.7.0")

    // The SqlDriver implementation wraps this rather than android.database
    // directly, so WAL and the pragmas are configured the same way expo-sqlite
    // configures them.
    implementation("androidx.sqlite:sqlite-framework:2.4.0")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")
}

