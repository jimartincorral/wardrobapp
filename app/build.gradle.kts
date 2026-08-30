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
// Absent by default, which is why the release build type below falls back to
// `app/debug.keystore` rather than failing: a contributor with no keystore must
// still be able to build, and until a real key exists that file is what every
// published APK has been signed with.
// The OAuth clients this app is, to Google.
//
// Committed rather than kept as secrets, and deliberately: an Android OAuth
// client has no client secret, and the id ships inside the APK where anybody can
// read it. What stops somebody else using it is the pair Google checks it
// against -- the application id and the signing certificate -- which is why there
// are two of these, and why they are registered against the fingerprints in
// Signing, above. Hiding the id would protect nothing and would mean a build
// nobody but its author could produce.
//
// Quoted twice: `buildConfigField` takes the Java source of the value, not the
// value.
val RELEASE_DRIVE_CLIENT_ID =
    "\"911335776498-44e7arm15lbfgssdmrmef3b8ghil6nt4.apps.googleusercontent.com\""
val DEBUG_DRIVE_CLIENT_ID =
    "\"911335776498-cdb983dqrt7mh2eti4pdlmo764eedbt7.apps.googleusercontent.com\""

val releaseStoreFile: String? = providers.gradleProperty("WARDROBAPP_STORE_FILE").orNull
val releaseStorePassword: String? = providers.gradleProperty("WARDROBAPP_STORE_PASSWORD").orNull
val releaseKeyAlias: String? = providers.gradleProperty("WARDROBAPP_KEY_ALIAS").orNull
val releaseKeyPassword: String? = providers.gradleProperty("WARDROBAPP_KEY_PASSWORD").orNull

android {
    namespace = "com.wardrobapp.app"
    // Unchanged from what the app this replaced was built against, so nothing
    // about the platform surface moved when the app did.
    compileSdk = 36

    defaultConfig {
        // The id the installed app has. This build replaces it, in place: same
        // id, same signing key, a higher version code, and the wardrobe already
        // in `files/SQLite/` and `files/garment-images/` is where this app looks
        // for it (`wardrobeFilesIn` in :data). Nobody uninstalls anything and
        // nobody restores a backup to move.
        //
        // Not renamed to something tidier than `com.anonymous.*` -- Expo's
        // default, and an odd name for an app. An application id is an identity
        // rather than a label: changing it makes this a different app that
        // cannot upgrade the installed one, which is exactly the cost this
        // commit exists to avoid.
        applicationId = "com.anonymous.wardrobapp"
        minSdk = 24
        targetSdk = 36
        // Continues the sequence the published builds were numbered with --
        // 1000 + the CI run number -- because a version code that goes
        // backwards makes every phone refuse the build as a downgrade. The run
        // number keeps climbing in the same workflow, so the next release is
        // above the last one published by the app this replaces.
        versionCode = versionCodeOffset + ciRunNumber
        versionName = "1.1.0"
    }

    // Per-app language, the way Google documents it: the locale list is
    // generated from the `values-*` directories rather than hand-written, so
    // adding a language is adding a directory and nothing else. Needs AGP 8.1
    // and compileSdk 33; this is on 8.9.1 and 36.
    //
    // It also needs res/resources.properties naming the locale that the
    // unqualified `values/` directory holds -- without it the build fails
    // rather than assuming, which is the right way round for a question with no
    // safe default.
    androidResources {
        generateLocaleConfig = true
    }

    lint {
        // Every check lint runs by default, and a warning fails the build.
        //
        // The usual way to get here is a baseline file that freezes the existing
        // findings, and it turned out not to be needed: the full run found 0
        // errors and 34 warnings, and all 34 were worth fixing or worth saying
        // why not -- ten missing Spanish plural quantities, five launcher icons
        // that filled their square, five KTX extensions, a redundant label, the
        // backup rules Android 12 wants, and two strings with two counts in them
        // that no <plurals> can inflect (told so where they are written). So
        // there is no baseline: the backlog is empty rather than frozen, and
        // there is nowhere for a new finding to hide.
        warningsAsErrors = true

        // Except the checks that go off when somebody else publishes a release.
        // A newer AndroidX or AGP is worth knowing about and is not a defect in
        // this commit; leaving them as errors would turn CI red on a morning
        // nobody touched the code, which is how a red build stops meaning
        // anything. Still reported, just not fatal.
        informational += setOf(
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "NewerVersionAvailable",
        )

        // So CI can print the findings into the build log. The HTML report is no
        // use to anyone reading a workflow run.
        textReport = true
    }

    signingConfigs {
        /*
         * The key every published build carried until 28 August 2026.
         *
         * It is the public debug keystore that ships inside
         * `expo-template-bare-minimum`, and it signed every APK the React
         * Native app ever published -- verified rather than assumed: the
         * certificate in the `nightly` APK and the certificate in this file are
         * the same one, SHA-256
         * FA:C6:17:45:DC:09:03:78:6F:B9:ED:E6:2A:96:2B:39:9F:73:48:F0:BB:6F:89:9B:83:32:66:75:91:03:3B:9C,
         * CN=Android Debug, valid to 2052.
         *
         * That is why it is committed, `.gitignore`'s rule about keystores
         * notwithstanding. Android will only replace an installed app with one
         * signed by the same key, so signing with this is what makes the Kotlin
         * app an upgrade of the app people have rather than a second app they
         * have to install after uninstalling the first and restoring a backup
         * into it. Nothing is leaked by committing it: the file is inside a
         * public npm package, and it has been the app's signing identity all
         * along -- this makes that explicit rather than incidental.
         *
         * What it cost was real: anybody can sign an APK with this key, so a
         * build claiming to be an update could not be distinguished from one.
         * That price has since been paid. Published builds are signed with a
         * release key only its owner holds -- the `release` config below, fed by
         * four repository secrets -- which cost one back-up, uninstall and
         * restore per device, once, in August 2026.
         *
         * So this config no longer signs anything that gets published. It is the
         * fallback for a build with no keystore configured: a fork, or a
         * contributor without the secrets, who must still be able to assemble a
         * release. That is why it stays, and why it stays committed.
         */
        create("installedBase") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

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
        debug {
            // So a development build sits beside the real app instead of
            // fighting it for the same id -- which, with the same signing key,
            // would mean overwriting somebody's actual wardrobe with a debug
            // build. `android:authorities` is `${applicationId}.camera`, so the
            // FileProvider follows the suffix on its own.
            applicationIdSuffix = ".debug"

            // Its own OAuth client, because Google keys an Android client on the
            // application id *and* the signing certificate, and a debug build
            // differs in both: `.debug` on the id, and the committed debug key
            // rather than the release one. One client cannot cover both.
            buildConfigField("String", "DRIVE_CLIENT_ID", DEBUG_DRIVE_CLIENT_ID)
            manifestPlaceholders["appAuthRedirectScheme"] = "com.anonymous.wardrobapp.debug"
        }

        release {
            // R8 on. Worth knowing what this is and is not verified by: CI runs
            // `assembleRelease`, so a rule that strips something the build can see
            // fails there -- but nothing in CI ever *runs* the APK, so anything
            // reached by name at runtime is only as safe as `proguard-rules.pro`
            // says it is. The four that matter are the Drive sign-in, background
            // removal, cropping, and the scheduled backup.
            //
            // Resource shrinking is deliberately not on with it. It is a second
            // question with its own failure mode, and answering two at once means
            // not knowing which one broke.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "DRIVE_CLIENT_ID", RELEASE_DRIVE_CLIENT_ID)
            manifestPlaceholders["appAuthRedirectScheme"] = "com.anonymous.wardrobapp"
            // A real keystore when one is configured; otherwise the committed
            // one, whose whole purpose is that it is the key the installed base
            // already trusts. See `signingConfigs` above.
            signingConfig = if (releaseStoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("installedBase")
            }
        }
    }

    buildFeatures {
        compose = true
        // For DRIVE_CLIENT_ID, which differs per build type and so cannot be a
        // constant in the source.
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged resources and the manifest, which is
            // also how it learns which SDK to emulate (targetSdk, above).
            isIncludeAndroidResources = true
        }
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
    // Only for per-app language. The platform API arrived in Android 13 and this
    // app supports 24, so the choice goes through AppCompatDelegate, which
    // backports it -- and which is why MainActivity is an AppCompatActivity and
    // the manifest holds a locales service. Compose needs no other part of this
    // library.
    implementation("androidx.appcompat:appcompat:1.7.0")
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

    // OAuth for Drive backups: the authorization request, PKCE, the token
    // exchange and the refresh. Not hand-rolled, unlike the rest of this app's
    // networking -- the update check fetches a public document, where the worst
    // a mistake costs is a failed download, and this holds a credential to
    // somebody's Google account. It is also not Play Services: GoogleSignIn is
    // deprecated in favour of an API still settling, and AppAuth keeps the auth
    // path working on a phone with no Google services at all.
    implementation("net.openid:appauth:0.11.1")

    // The weekly backup. WorkManager rather than an alarm because the job has
    // conditions -- a network that is not somebody's data plan, and a battery that
    // is not nearly flat -- and because it has to survive a reboot: a safety net
    // that forgets it exists after a restart is not one.
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Photos are files on disk; Coil loads them without hand-rolled decoding.
    implementation("io.coil-kt:coil-compose:2.7.0")

    // The crop screen a photo goes through on its way in. Android has none to
    // call -- ACTION_CROP is an undocumented intent that most phones answer with
    // nothing -- so expo-image-picker's `allowsEditing`, which is what the React
    // Native app cropped with, was this library. Same one, so a photo is framed
    // the way it always was.
    implementation("com.vanniktech:android-image-cropper:4.7.0")

    // The SqlDriver implementation wraps this rather than android.database
    // directly, so WAL and the pragmas are configured the same way expo-sqlite
    // configures them.
    implementation("androidx.sqlite:sqlite-framework:2.4.0")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // The only tests in this module, and the only ones in the project that need
    // Android. Everything decidable without a device lives in :domain, :data and
    // :presentation and is tested there; what is left here is the platform
    // plumbing -- and one piece of it, where the database file actually lands,
    // was wrong for weeks precisely because no test could see it.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")

    // Compose UI tests, run by Robolectric rather than on a device. That is the
    // whole point: these assert what a screen shows, and asserting it in the same
    // `:app:test` task as everything else means CI needs no emulator. They read the
    // semantics tree, not pixels, so no graphics mode is involved.
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // Supplies the activity `createComposeRule` starts the composition in. On the
    // debug manifest, which is the one Robolectric merges.
    //
    // Debug-only because that is how it is published -- it exists to add an
    // activity to a manifest, and no release build should carry one. The
    // consequence is that `:app:test` fails: it runs the unit tests against both
    // variants, and in release there is no activity for these to launch. So the
    // test task is named by variant in CI and in the README. Disabling the release
    // unit-test variant outright (`androidComponents { beforeVariants ... }`) would
    // say it once instead of twice, and is worth doing by whoever next has an SDK
    // in front of them to verify it against.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

