import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.20"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(project(":parity-testing"))
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
