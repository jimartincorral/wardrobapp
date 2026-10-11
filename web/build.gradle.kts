import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.binaryen.BinaryenExec

// The browser app: the screens in :ui, driven by the screen models in
// :presentation, answered by the Home Assistant server through :api's sources.
//
// The browser's MainActivity, in other words -- navigation, and handing each
// screen its model -- and nothing a screen draws or decides. Wasm alone: it is
// what the server hands a browser, and nothing else runs it.
plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

repositories {
    mavenCentral()
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        moduleName = "wardrobapp"
        browser {
            commonWebpackConfig {
                outputFileName = "wardrobapp.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":ui"))
            implementation(project(":api"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material3)
            // The browser's own fetch, which is the only way a page makes a
            // request: cookies, and Home Assistant's ingress session with them,
            // come along as for any other request the page makes.
            implementation("io.ktor:ktor-client-js:3.1.3")
            // Coil, which :ui's screens draw photos with, given the network
            // module :ui deliberately leaves out: on the phone every photo is a
            // file, and here every photo is on the server. See PhotoLoading.kt.
            // 3.0.4, :ui's, for the reason ui/build.gradle.kts gives.
            implementation("io.coil-kt.coil3:coil-compose:3.0.4")
            implementation("io.coil-kt.coil3:coil-network-ktor3:3.0.4")
        }
    }
}

// Binaryen's optimiser without its passes, when the build says so: what the
// Home Assistant app's workflow asks for on every run that will not publish
// an image, which is every pull request and every push to main that leaves the
// version alone.
//
// The optimiser was most of that workflow's time -- 6 minutes 48 of the 9 the
// Gradle step took, for a bundle nobody would download -- and it is never
// cached (Kotlin marks the task DisableCachingByDefault), so it ran in full
// even when nothing in the browser app had changed. Without its passes it
// still reads and writes the module, in seconds, so a wasm file Binaryen
// cannot parse still fails the build; what is lost is only the shrinking. A
// published image is built without this and gets all of it -- 1.58 MB
// gzipped, against 1.75 for a single -O3 pass and 2.62 for none.
//
// The feature flags stay, because they are what lets Binaryen read Kotlin's
// output at all (GC, exception handling); everything else Kotlin passes it is
// optimisation. Filtered from Kotlin's own list rather than written out here,
// so a flag a later Kotlin needs is kept without anybody having to notice.
if (providers.gradleProperty("wardrobapp.quickWasm").orNull == "true") {
    tasks.withType<BinaryenExec>().configureEach {
        binaryenArgs = binaryenArgs.filter { it.startsWith("--enable-") }.toMutableList()
    }
}
