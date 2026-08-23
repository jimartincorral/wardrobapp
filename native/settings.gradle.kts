// The native Android port. Kept alongside the React Native app rather than
// replacing it: the RN app keeps shipping until this reaches parity.
//
// Every module here is deliberately a plain Kotlin/JVM one, not an Android one,
// so the whole thing builds and tests without the Android SDK -- on any machine
// and in CI. The Android-specific layers (SQLite, filesystem, Compose) arrive as
// separate modules later; keeping the pure parts pure is what lets them be
// verified anywhere.
rootProject.name = "wardrobapp-native"

include(":domain")
include(":data")
include(":parity-testing")

// The Compose app is the one module that genuinely needs the Android SDK, so it
// is included only where one is present. That keeps `./gradlew test` working on
// a machine with nothing but a JDK -- which is the whole reason the other
// modules are plain Kotlin -- while CI, which has the SDK, builds everything.
//
// Detection mirrors what the Android Gradle Plugin itself looks for.
val androidSdk = System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: file("local.properties")
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
