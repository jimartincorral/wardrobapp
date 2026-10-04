import java.net.URI
import java.security.MessageDigest
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

val onnxRuntimeVersion = "1.30.0"

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
    // Background removal: runs the model BackgroundRemover is about. Its jar
    // carries the native library for Linux on x86-64 and on 64-bit ARM, which
    // are the two images the Home Assistant app is built for, and for macOS
    // and Windows, which the distribution leaves out (see below).
    implementation("com.microsoft.onnxruntime:onnxruntime:$onnxRuntimeVersion")
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

// HomeAssistantAppTest reads the Home Assistant app's files -- its config.yaml,
// its Dockerfile, the workflow that builds its images -- to hold them to the
// server and to each other. Declared as inputs, so editing one of them reruns
// the test rather than replaying a cached pass.
tasks.withType<Test>().configureEach {
    systemProperty("repositoryDir", rootDir.absolutePath)
    inputs.dir(rootProject.file("homeassistant"))
        .withPropertyName("homeAssistantApp")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        rootProject.file(".github/workflows/home-assistant.yml"),
        rootProject.file(".dockerignore"),
        rootProject.file("repository.yaml"),
    )
        .withPropertyName("homeAssistantBuild")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

application {
    mainClass = "com.wardrobapp.server.MainKt"
    applicationName = "wardrobapp-server"
}

// The model BackgroundRemover runs: silueta, a 44 MB U²-Net, as rembg
// publishes it. Downloaded rather than committed -- the repository would carry
// every version of it forever -- and checked against the digest it had when it
// was chosen, so a file swapped at the source is a failed build rather than a
// different model in Home Assistant. Downloaded once, into the build
// directory, and again only after a clean.
//
// The tests that run the real model read it from here; the distribution
// carries it in models/, which is where the Dockerfile tells the server to
// look.
val backgroundModel = layout.buildDirectory.file("models/silueta.onnx")

val downloadBackgroundModel by tasks.registering {
    val url = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/silueta.onnx"
    val sha256 = "75da6c8d2f8096ec743d071951be73b4a8bc7b3e51d9a6625d63644f90ffeedb"
    inputs.property("url", url)
    inputs.property("sha256", sha256)
    outputs.file(backgroundModel)
    outputs.cacheIf { true }
    doLast {
        val target = backgroundModel.get().asFile
        target.parentFile.mkdirs()
        val partial = File(target.parentFile, "${target.name}.part")
        URI(url).toURL().openStream().use { input ->
            partial.outputStream().use { output -> input.copyTo(output) }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(partial.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (digest != sha256) {
            partial.delete()
            throw GradleException("The background model at $url is not the one chosen: SHA-256 $digest, expected $sha256.")
        }
        partial.renameTo(target) || throw GradleException("Could not move the background model into $target.")
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(downloadBackgroundModel)
    systemProperty("backgroundModel", backgroundModel.get().asFile.absolutePath)
}

// ONNX Runtime's jar, without the macOS and Windows libraries in it: a
// hundred and thirty megabytes of image, macOS's debug symbols most of it,
// that no Home Assistant machine could load. Repackaged under its own name,
// so the start script, which lists the jars by name, finds it unchanged.
val onnxRuntimeForLinux by tasks.registering(Jar::class) {
    val jar = configurations.runtimeClasspath.map { classpath ->
        classpath.files.single { it.name == "onnxruntime-$onnxRuntimeVersion.jar" }
    }
    from(jar.map { zipTree(it) }) {
        exclude("ai/onnxruntime/native/osx-*/**", "ai/onnxruntime/native/win-*/**")
    }
    archiveFileName = "onnxruntime-$onnxRuntimeVersion.jar"
    destinationDirectory = layout.buildDirectory.dir("onnxruntime-linux")
}

distributions {
    main {
        contents {
            exclude { it.name == "onnxruntime-$onnxRuntimeVersion.jar" && !it.file.path.contains("onnxruntime-linux") }
            from(onnxRuntimeForLinux) { into("lib") }
            from(downloadBackgroundModel) { into("models") }
        }
    }
}
