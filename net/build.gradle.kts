import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The network side of URL import: requesting a product page and its images.
//
// Plain Kotlin/JVM, like :domain, :data and :presentation, but kept out of all
// three on purpose. Those hold decisions, and :data's own build file says that
// file and network access do not belong in it; this holds the I/O those decisions
// are about. It is a module rather than a corner of :app for two reasons. :app
// only compiles where there is an Android SDK -- so the code that decides what
// address a request reaches could otherwise only be tested in CI -- and the Home
// Assistant server planned in the multiplatform work fetches pages the same way,
// on a JVM that is not Android. Neither needs anything from Android, and
// `ImportHttpTest` runs them against a real HTTP server on loopback.
plugins {
    kotlin("jvm") version "2.1.20"
}

repositories {
    mavenCentral()
}

dependencies {
    // `api` because `PageFetcher` and `FetchedPage` are this module's public
    // surface, and what it refuses with is :domain's `UnsafeUrlException`.
    api(project(":domain"))
    testImplementation(kotlin("test"))
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
