// Wardrobapp.
//
// :domain, :data, :presentation and :net need nothing but a JDK, so they build
// and test without the Android SDK -- on any machine and in CI. :app needs the
// SDK, and is included only where one exists; :ui, the screens, builds fully
// where there is one and for the browser alone where there is not. That split started as
// a way to port the app a layer at a time; it is kept because it is the reason
// most of this codebase can be tested in seconds without an emulator.
//
// The SDK probe is repeated rather than shared: pluginManagement is evaluated in
// an early scope that cannot see declarations from the rest of the file.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Only when there is an SDK to build against. Google's Maven is
        // unreachable in some sandboxes, and an unreachable repository breaks
        // resolution for the pure modules too -- the ones meant to build
        // anywhere.
        val hasSdk = System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
                ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }
                ?: java.io.File(rootDir, "local.properties")
                    .takeIf { it.exists() }
                    ?.readLines()
                    ?.firstOrNull { it.startsWith("sdk.dir=") }
                    ?.removePrefix("sdk.dir=")
        if (hasSdk != null) google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        val hasSdk = System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
                ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }
                ?: java.io.File(rootDir, "local.properties")
                    .takeIf { it.exists() }
                    ?.readLines()
                    ?.firstOrNull { it.startsWith("sdk.dir=") }
                    ?.removePrefix("sdk.dir=")
        if (hasSdk != null) google()
    }
}

rootProject.name = "wardrobapp"

include(":domain")
include(":data")
include(":presentation")
include(":net")

val androidSdk = System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
                ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }
                ?: java.io.File(rootDir, "local.properties")
                    .takeIf { it.exists() }
                    ?.readLines()
                    ?.firstOrNull { it.startsWith("sdk.dir=") }
                    ?.removePrefix("sdk.dir=")

// :app needs the SDK, so it is included only where there is one. :ui always is,
// but where there is no SDK it is built from build.wasm.gradle.kts: the same
// screens, compiled for the browser alone. Its full build needs Google's Maven
// twice over -- for the Android library plugin, and for the androidx artifacts
// Compose Multiplatform's Android and desktop builds resolve to -- which is the
// repository the probe above leaves out. Its Wasm build needs only Maven
// Central, so the screens are compiled on every machine that runs
// `./gradlew test`. build.wasm.gradle.kts says the rest.
include(":ui")

if (androidSdk != null && file(androidSdk).isDirectory) {
    include(":app")
} else {
    project(":ui").buildFileName = "build.wasm.gradle.kts"
    logger.lifecycle(
        "No Android SDK found (ANDROID_HOME, ANDROID_SDK_ROOT or local.properties) -- " +
            "skipping :app, and building :ui for the browser only. The pure modules " +
            "still build and test."
    )
}
