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
    // A ceiling on what a request may carry; see bodyLimit in WardrobeApi.
    implementation("io.ktor:ktor-server-body-limit:3.1.3")
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

/**
 * Download [url] to [target], keeping it only if its SHA-256 is [sha256]: a
 * file swapped at the source is a failed build, not a different model in
 * Home Assistant. Written to a `.part` beside the target first, so a download
 * cut short is never taken for the file. [what] names it in the error.
 */
fun downloadVerified(url: String, sha256: String, target: File, what: String) {
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
        throw GradleException("The $what at $url is not the one chosen: SHA-256 $digest, expected $sha256.")
    }
    partial.renameTo(target) || throw GradleException("Could not move the $what into $target.")
}

val downloadBackgroundModel by tasks.registering {
    val url = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/silueta.onnx"
    val sha256 = "75da6c8d2f8096ec743d071951be73b4a8bc7b3e51d9a6625d63644f90ffeedb"
    inputs.property("url", url)
    inputs.property("sha256", sha256)
    outputs.file(backgroundModel)
    outputs.cacheIf { true }
    doLast { downloadVerified(url, sha256, backgroundModel.get().asFile, "background model") }
}

// The model StyleEncoder runs, and the anchors it reads attributes with: the
// image half of OpenAI's CLIP ViT-B/32, quantised to 8 bits, and the text
// half's embeddings of a few sentences per attribute value. Both made by
// scripts/export-style-model.py and published by .github/workflows/
// style-model.yml as a release of this repository, which is where they are
// fetched from -- by digest, for the reason the background model is. A new
// export is a new release tag, and new digests here.
val styleRelease = "https://github.com/jimartincorral/wardrobapp/releases/download/style-model-v1"
val styleModel = layout.buildDirectory.file("models/style-image-vitb32-int8.onnx")
val styleAnchors = layout.buildDirectory.file("models/style-anchors.json")

val downloadStyleModel by tasks.registering {
    val modelSha256 = "3c4250fe483e4e36f272f95ba122b603226ece047bd791cb1266265ae5a78b5b"
    val anchorsSha256 = "a2129312f64762b2932eb6c71f37a552b5bcb7a83feb90c130158ac21e843243"
    inputs.property("release", styleRelease)
    inputs.property("modelSha256", modelSha256)
    inputs.property("anchorsSha256", anchorsSha256)
    outputs.files(styleModel, styleAnchors)
    outputs.cacheIf { true }
    doLast {
        val model = styleModel.get().asFile
        val anchors = styleAnchors.get().asFile
        downloadVerified("$styleRelease/${model.name}", modelSha256, model, "style model")
        downloadVerified("$styleRelease/${anchors.name}", anchorsSha256, anchors, "style anchors")
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(downloadBackgroundModel, downloadStyleModel)
    systemProperty("backgroundModel", backgroundModel.get().asFile.absolutePath)
    systemProperty("styleModel", styleModel.get().asFile.absolutePath)
    systemProperty("styleAnchors", styleAnchors.get().asFile.absolutePath)
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
            from(downloadStyleModel) { into("models") }
        }
    }
}
