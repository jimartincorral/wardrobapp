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
// decisions the phone's do. Common code apart from two things: the date
// formatting in StoredDates, which is the JVM's locale-aware DateFormat and gets
// a browser counterpart when the screens that call it move, and the Drive backup
// schedule, which is a phone feature. The one thing the platforms genuinely do
// differently -- how a reader's language sorts -- is `readerOrder`, an expect
// with each platform's own collator behind it.
plugins {
    kotlin("multiplatform") version "2.1.20"
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

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs()

    sourceSets {
        commonMain.dependencies {
            api(project(":data"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            // Only for ArchiveMessageParityTest, which asks UnrestorableReason for
            // its sealed subclasses to prove it has a sample of every one. Nothing
            // ships it.
            implementation(kotlin("reflect"))
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
    // And the glyph sources, for GlyphSourcesTest, which checks that the
    // generated vectors in :app still say what the SVGs do. An input for the same
    // reason as the resources above: without it, editing an SVG would leave the
    // test UP-TO-DATE and the check silently not run.
    val glyphSources = rootProject.file("art/glyphs")
    systemProperty("glyphSourceDir", glyphSources.absolutePath)
    inputs.dir(glyphSources)
        .withPropertyName("glyphSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    val appSources = rootProject.file("app/src/main/kotlin/com/wardrobapp/app")
    systemProperty("appSourceDir", appSources.absolutePath)
    inputs.dir(appSources)
        .withPropertyName("appScreenSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
