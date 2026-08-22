import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The mapping layer: database rows and photo references into domain types.
//
// Pure Kotlin/JVM on purpose. This is the code that decides whether an existing
// wardrobe opens correctly, so it is the code most worth being able to test
// anywhere. The SQLite and filesystem access that will use it goes in an Android
// module; none of it belongs here.
plugins {
    kotlin("jvm") version "2.1.20"
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":domain"))
    // The list columns hold JSON, so parsing it is a production concern here,
    // not just a test one.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
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
