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
