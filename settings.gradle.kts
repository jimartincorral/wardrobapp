// Wardrobapp.
//
// :domain, :data and :presentation are deliberately plain Kotlin/JVM, so they
// build and test without the Android SDK -- on any machine and in CI. Only :app
// needs the SDK, and it is included only where one exists. That split started as
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

val androidSdk = System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
                ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }
                ?: java.io.File(rootDir, "local.properties")
                    .takeIf { it.exists() }
                    ?.readLines()
                    ?.firstOrNull { it.startsWith("sdk.dir=") }
                    ?.removePrefix("sdk.dir=")

if (androidSdk != null && file(androidSdk).isDirectory) {
    include(":app")
} else {
    logger.lifecycle(
        "No Android SDK found (ANDROID_HOME, ANDROID_SDK_ROOT or local.properties) -- " +
            "skipping :app. The pure modules still build and test."
    )
}
