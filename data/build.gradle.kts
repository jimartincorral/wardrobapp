import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The mapping layer: database rows and photo references into domain types.
//
// No Android in it, on purpose. This is the code that decides whether an existing
// wardrobe opens correctly, so it is the code most worth being able to test
// anywhere. The SQLite access it runs through is a small interface with two
// implementations: `AndroidSqlDriver` in :app, and `JdbcSqlDriver` here, which
// the tests and the Home Assistant server use.
//
// Kotlin Multiplatform, like :domain. The records, the queries, the writes and
// the schema are common code, because the browser version shows the same
// records the phone does. What stays on the JVM is what touches files, a
// network or a clock: the backup archive's zip and file handling, where the
// wardrobe lives on disk, the Drive requests, the reopening driver's locking,
// and the timestamps a write stamps rows with. All of it runs only on the phone,
// and later the Home Assistant server, which are both JVMs.
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
            api(project(":domain"))
            // The list columns hold JSON, so parsing it is a production concern
            // here, not just a test one.
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            // Real SQLite, so the read paths are exercised against the schema the
            // app actually applies rather than a stand-in. Only here, never in
            // jvmMain: JdbcSqlDriver compiles against java.sql alone, so the
            // driver behind it is chosen by whoever runs it -- the tests here,
            // :server in production -- and the phone, which uses
            // SupportSQLiteDatabase instead, never carries it.
            implementation("org.xerial:sqlite-jdbc:3.50.1.0")
        }
    }
}
