import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Shared machinery for the parity suites, in `main` rather than `test` so more
// than one module can depend on it. Nothing ships this: it is only ever a
// testImplementation dependency.
plugins {
    kotlin("jvm") version "2.1.20"
}

repositories {
    mavenCentral()
}

dependencies {
    // api, not implementation: consumers write assertions against JsonObject.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    api(kotlin("test"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}
