import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestResult
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.20"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    // Test-only, and used through the untyped JsonElement API so no
    // serialization compiler plugin is needed: the parity fixtures are read,
    // never written, and nothing in main/ depends on this.
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
}

// Pinned to 17 -- the JDK the Android build uses -- so this module can be
// consumed by the app module unchanged, whatever JDK the developer happens to
// have. Java and Kotlin must agree or Gradle rejects the build.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// With no test sources at all, Gradle marks the test task NO-SOURCE and skips
// it -- no listener runs, and the build goes green having verified nothing. That
// is the failure mode most worth catching here, so it gets its own task, which
// declares no inputs and therefore always runs.
val verifyTestSourcesExist by tasks.registering {
    val testSources = sourceSets.test.get().allSource
    doLast {
        if (testSources.files.none { it.extension == "kt" }) {
            throw GradleException(
                "No Kotlin test sources found. The parity suite is the point of this " +
                    "module, so its absence is a failure, not a pass."
            )
        }
    }
}

tasks.test {
    dependsOn(verifyTestSourcesExist)
    useJUnitPlatform()
    testLogging { events("failed") }

    // A test task that discovers nothing reports success, which looks identical
    // to a task that ran everything. Given the whole point of this module is a
    // suite asserting the port matches the TypeScript, "no tests found" has to be
    // a failure rather than a green tick. (Gradle's own
    // failOnNoDiscoveredTests only arrives in 9.x.)
    afterSuite(
        KotlinClosure2<TestDescriptor, TestResult, Unit>({ descriptor, result ->
            if (descriptor.parent == null && result.testCount == 0L) {
                throw GradleException(
                    "No tests were discovered. The parity suite is the point of this module, " +
                        "so an empty run is a failure, not a pass."
                )
            }
        })
    )
}
