// The native Android port. Kept alongside the React Native app rather than
// replacing it: the RN app keeps shipping until this reaches parity.
//
// :domain, :data and :presentation are deliberately plain Kotlin/JVM, so they
// build and test without the Android SDK -- on any machine and in CI. Only :app
// needs the SDK, and it is included only where one exists.
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
        val hasSdk = System.getenv("ANDROID_HOME")
                ?: System.getenv("ANDROID_SDK_ROOT")
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
        val hasSdk = System.getenv("ANDROID_HOME")
                ?: System.getenv("ANDROID_SDK_ROOT")
                ?: java.io.File(rootDir, "local.properties")
                    .takeIf { it.exists() }
                    ?.readLines()
                    ?.firstOrNull { it.startsWith("sdk.dir=") }
                    ?.removePrefix("sdk.dir=")
        if (hasSdk != null) google()
    }
}

rootProject.name = "wardrobapp-native"

include(":domain")
include(":data")
include(":presentation")
include(":parity-testing")

val androidSdk = System.getenv("ANDROID_HOME")
                ?: System.getenv("ANDROID_SDK_ROOT")
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
