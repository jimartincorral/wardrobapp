import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The wardrobe, served: what the Home Assistant app runs.
//
// A JVM program, because the phone's data layer is JVM code -- the queries, the
// writes, the import's address checks, the backup format -- and a server that
// ran all of it unchanged was the reason Phase 2 put the screens' logic behind
// sources in the first place. It holds a wardrobe the way the phone does, in
// the same SQLite schema and the same photo directory layout, and answers the
// routes in :api with :presentation's Database sources: the browser asks, the
// code that answers the phone answers it.
//
// No Android, so it builds and tests on any machine, like the modules it uses.
// `application` rather than a fat jar: the container image copies what
// `installDist` lays out, and the start script it writes is what runs.
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":api"))
    // URL import: the address checks and a page fetcher that reaches only the
    // addresses they allow, as on the phone.
    implementation(project(":net"))

    // The same line as :api's client, for the reason given there.
    implementation("io.ktor:ktor-server-core:3.1.3")
    implementation("io.ktor:ktor-server-cio:3.1.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.1.3")
    implementation("io.ktor:ktor-server-status-pages:3.1.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")

    // What JdbcSqlDriver opens a database with. Here and nowhere else in
    // production: :data compiles against java.sql alone so that the phone,
    // which has SQLite of its own, never carries this.
    runtimeOnly("org.xerial:sqlite-jdbc:3.50.1.0")
    // Somewhere for Ktor's log lines to go: Home Assistant shows an app's
    // standard output as its log.
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:3.1.3")
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

application {
    mainClass = "com.wardrobapp.server.MainKt"
    applicationName = "wardrobapp-server"
}
