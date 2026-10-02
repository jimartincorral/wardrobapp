import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The mapping layer: database rows and photo references into domain types.
//
// No Android in it, on purpose. This is the code that decides whether an existing
// wardrobe opens correctly, so it is the code most worth being able to test
// anywhere. The SQLite and filesystem access that uses it lives elsewhere --
// `AndroidSqlDriver` in :app, the JDBC driver in the tests.
//
// Kotlin Multiplatform, like :domain. The records, the queries, the writes and
// the schema are common code, because the browser version shows the same
// records the phone does. What stays on the JVM is what touches files, a
// network or a clock: the backup archive's zip and file handling, where the
// wardrobe lives on disk, the Drive requests, the reopening driver's locking,
// and the timestamps a write stamps rows with. All of it runs only on the phone,
// and later the Home Assistant server, which are both JVMs.
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
            api(project(":domain"))
            // The list columns hold JSON, so parsing it is a production concern
            // here, not just a test one.
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            // Real SQLite, so the read paths are exercised against the schema the
            // app actually applies rather than a stand-in. Test-only: the Android
            // implementation of SqlDriver wraps SupportSQLiteDatabase instead.
            implementation("org.xerial:sqlite-jdbc:3.50.1.0")
        }
    }
}
