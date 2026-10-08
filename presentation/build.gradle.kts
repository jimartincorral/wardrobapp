import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// What the screens show, as pure functions over records.
//
// Free of Android, like :domain and :data, and for the same reason: this is the
// logic that decides what a list contains and what a form will accept, and it is
// worth being able to test all of it without an emulator. Compose sits on top of
// this and renders -- it should hold layout, not decisions.
//
// Kotlin Multiplatform, like them, because the browser's screens make the same
// decisions the phone's do. Common code apart from the Drive backup schedule,
// which is a phone feature. The things the platforms genuinely do differently
// are expects with each platform's own answer behind it: how a reader's
// language sorts (`readerOrder`, a collator each), and how a date is written
// for them (`formatStoredDateForReader`, DateFormat on the JVM and
// Intl.DateTimeFormat in the browser). Which strings are dates at all is common,
// in StoredMoment, so that the two cannot disagree about it.
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

repositories {
    mavenCentral()
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    // In a browser: :web, the app the Home Assistant server hands out, is built
    // from this, and every library an executable uses has to say where it runs.
    // That configures browser tests as well, which nothing runs -- the root build
    // file says why `test` compiles Wasm rather than running it.
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            api(project(":data"))
            // For the screen models: each publishes its screen's state as a
            // StateFlow and does its work in a scope it is handed, which is
            // what lets one model drive a screen on the phone -- inside an
            // Android ViewModel -- and in the browser alike. 1.9.0, the version
            // :app already ships through kotlinx-coroutines-android, so the phone
            // gets no second copy.
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            // A virtual clock and a dispatcher the test controls, so a model's
            // asynchronous work runs to completion on demand rather than racing
            // the assertions.
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            // Only for ArchiveMessageParityTest, which asks UnrestorableReason for
            // its sealed subclasses to prove it has a sample of every one. Nothing
            // ships it.
            implementation(kotlin("reflect"))
            // Only for QrCodeTest, which reads every code QrCode draws back
            // with the decoder phones' scanners are built on. An encoder
            // checked against itself would agree with its own mistakes.
            implementation("com.google.zxing:core:3.5.3")
        }
    }
}

// Where :app keeps its string resources, for StringResourceParityTest.
//
// That test lives here, in a module that builds without the Android SDK, because
// :app does not -- so `MissingTranslation` and everything else in `:app:lint`
// only run in CI. Passing the path rather than letting the test walk up out of
// its own directory keeps the coupling visible.
tasks.withType<Test>().configureEach {
    val appResources = rootProject.file("app/src/main/res")
    systemProperty("appResDir", appResources.absolutePath)

    // And the manifest, for XmlWellFormedTest. It is not under res/, and it is the
    // one XML file in this project whose breakage stops the build outright rather
    // than failing a check -- `processDebugMainManifest` cannot parse it, so
    // nothing downstream runs.
    val appManifest = rootProject.file("app/src/main/AndroidManifest.xml")
    systemProperty("appManifest", appManifest.absolutePath)
    inputs.file(appManifest)
    // Declared as an input, not just handed over as a path. Without this Gradle
    // sees nothing in this module change when a string does, calls the test task
    // UP-TO-DATE and skips it -- so the check would pass once and then quietly
    // stop running. A mutation sweep found exactly that: six injected faults all
    // "passed".
    inputs.dir(appResources)
        .withPropertyName("appStringResources")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // And the screens themselves, for HardcodedStringTest. Same reasoning: :app
    // has no local compiler, so a test that reads its sources is the only check
    // available before CI.
    val appSources = rootProject.file("app/src/main/kotlin/com/wardrobapp/app")
    systemProperty("appSourceDir", appSources.absolutePath)
    inputs.dir(appSources)
        .withPropertyName("appScreenSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // And the glyph sources, for GlyphSourcesTest, which checks that the
    // generated vectors in :ui still say what the SVGs do. An input for the same
    // reason as the resources above: without it, editing an SVG would leave the
    // test UP-TO-DATE and the check silently not run.
    val glyphSources = rootProject.file("art/glyphs")
    systemProperty("glyphSourceDir", glyphSources.absolutePath)
    inputs.dir(glyphSources)
        .withPropertyName("glyphSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // And the screens' strings, which moved to :ui as Compose Multiplatform
    // resources: StringResourceParityTest reads both tables, the message parity
    // tests compare against these, and XmlWellFormedTest parses them. An input,
    // like :app's resources above, so that editing a string reruns the tests.
    val uiResources = rootProject.file("ui/src/commonMain/composeResources")
    systemProperty("uiResDir", uiResources.absolutePath)
    inputs.dir(uiResources)
        .withPropertyName("uiStringResources")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // And :ui's two build files, for UiBuildFilesTest, which holds them to the
    // same dependencies. Inputs for the same reason as everything above.
    // The Home Assistant app's icon, which LauncherIconTest checks with the
    // launcher icons because the same script cuts it from the same logo.
    inputs.file(rootProject.file("homeassistant/wardrobapp/icon.png"))
        .withPropertyName("homeAssistantIcon")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    val uiModule = rootProject.file("ui")
    systemProperty("uiModuleDir", uiModule.absolutePath)
    inputs.files(File(uiModule, "build.gradle.kts"), File(uiModule, "build.wasm.gradle.kts"))
        .withPropertyName("uiBuildFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // And where the generated vectors are, for GlyphSourcesTest. :ui has no local
    // compiler either -- it builds only where :app does -- so its files are read
    // from here too.
    // Where the types that cross the wire are declared -- every @Serializable
    // class in common code -- so WireTypesTest can insist on a sample of each.
    val wireSources = listOf("domain", "data", "presentation")
        .map { rootProject.file("$it/src/commonMain/kotlin") }
    systemProperty("wireSourceDirs", wireSources.joinToString(File.pathSeparator) { it.absolutePath })
    inputs.files(wireSources)
        .withPropertyName("wireSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    val uiSources = rootProject.file("ui/src/commonMain/kotlin/com/wardrobapp/ui")
    systemProperty("uiSourceDir", uiSources.absolutePath)
    inputs.dir(uiSources)
        .withPropertyName("uiSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The script that writes the changelog, for ReleaseNoteDestinationsTest,
    // which holds the destinations a note may name to the ones the app knows.
    val releaseNotesScript = rootProject.file("scripts/release-notes.py")
    systemProperty("releaseNotesScript", releaseNotesScript.absolutePath)
    inputs.file(releaseNotesScript)
        .withPropertyName("releaseNotesScript")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
