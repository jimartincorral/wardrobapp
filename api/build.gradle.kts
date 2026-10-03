import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The wardrobe over HTTP: what the browser asks the Home Assistant server, and
// how.
//
// Both halves of the conversation are written here, in common code, so they
// cannot drift apart: the routes, the bodies that are not already one of the
// screens' own types, the way a failure travels, and an implementation of each
// screen's source that asks the server instead of a database. :server answers
// these routes; the browser build of the screens is handed these sources. The
// screen models never learn the difference -- which is the point of the
// sources having been interfaces since Phase 2 began.
//
// JVM as well as Wasm, though nothing on a JVM ships these sources: :server's
// tests drive them against a real server, which is how both halves are tested
// against each other rather than each against what its author assumed.
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
            api(project(":presentation"))
            // Ktor's client, because it is the HTTP client that runs in a
            // browser compiled from Kotlin and the server is Ktor too. 3.1, the
            // line built on Kotlin 2.1.20 -- this build's compiler -- so nothing
            // here was compiled by a newer Kotlin than the one reading it.
            api("io.ktor:ktor-client-core:3.1.3")
            implementation("io.ktor:ktor-client-content-negotiation:3.1.3")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(kotlin("reflect"))
        }
    }
}
