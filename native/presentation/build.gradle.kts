import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// What the screens show, as pure functions over records.
//
// Plain Kotlin/JVM, like :domain and :data, and for the same reason: this is the
// logic that decides what a list contains and what a form will accept, and it is
// worth being able to test all of it without an emulator. Compose sits on top of
// this and renders -- it should hold layout, not decisions.
plugins {
    kotlin("jvm") version "2.1.20"
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":data"))
    testImplementation(kotlin("test"))
    testImplementation(project(":parity-testing"))
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
