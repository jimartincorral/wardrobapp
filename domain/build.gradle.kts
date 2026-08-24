import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.20"
}

repositories {
    mavenCentral()
}

dependencies {
    // JSON-LD. A product page describes itself in JSON, so parsing it is a
    // production concern here rather than a test one -- the same reason :data
    // depends on this. No new weight in the APK: :data already ships it.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation(kotlin("test"))
}

// Pinned to 17 -- the JDK the Android build uses -- so these modules can be
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
