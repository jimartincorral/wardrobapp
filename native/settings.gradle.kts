// The native Android port. Kept alongside the React Native app rather than
// replacing it: the RN app keeps shipping until this reaches parity.
//
// `:domain` is deliberately a plain Kotlin/JVM module, not an Android one. It is
// the port of src/domain -- algorithms with no platform dependency -- so it
// builds and tests without the Android SDK, on any machine and in CI.
rootProject.name = "wardrobapp-native"

include(":domain")
