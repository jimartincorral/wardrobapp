import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

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
