import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The algorithms, the catalogue, and what a URL import says back.
//
// Kotlin Multiplatform since the screens started moving to Compose
// Multiplatform: the browser version needs the same garments, seasons and
// occasions the phone does, so most of this module is common code and compiles
// for the JVM and for Wasm. What stays JVM-only is the URL import itself -- the
// extraction, the address checks, `WebAddress`'s use of `java.net.IDN` -- because
// it only runs where a page is fetched, on the phone and on the Home Assistant
// server, and a second implementation of a safety check for a browser that never
// fetches anything would be a second place for it to be wrong.
//
// The tests are JVM tests. They run against the common code too, compiled for
// the JVM; see the root build file for why Wasm is compiled and not yet run.
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

repositories {
    mavenCentral()
}

kotlin {
    jvm {
        // Pinned to 17 -- the JDK the Android build uses -- so this can be
        // consumed by the app module unchanged, whatever JDK the developer has.
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs()

    sourceSets {
        commonMain.dependencies {
            // JSON-LD. A product page describes itself in JSON, so parsing it is a
            // production concern here rather than a test one -- the same reason
            // :data depends on this. No new weight in the APK: :data already ships
            // it. Common, because the library is.
            //
            // `api`, and the serialization compiler plugin above, since the
            // garments, outfits and everything else a screen is handed started
            // crossing HTTP: the Home Assistant server sends them to the browser
            // as JSON. The types carry `@Serializable` themselves rather than
            // being copied into transfer objects, so a field added to one is on
            // the wire without a second edit. A serializer is part of a type's
            // public face once it has one, so the library that defines it is
            // exposed with it.
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
