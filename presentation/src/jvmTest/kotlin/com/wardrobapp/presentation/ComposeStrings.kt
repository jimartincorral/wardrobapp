package com.wardrobapp.presentation

import java.io.File

/**
 * A string from :ui's tables as a screen receives it, not as the file spells it.
 *
 * For the tests that compare those strings against Kotlin -- the message parity
 * tests -- which used to read :app's tables and undo Android's escaping. The
 * screens' strings now live in Compose Multiplatform's resources, and its
 * escaping is not Android's: in 1.7 its `handleSpecialCharacters` turns `\uXXXX`,
 * `\n` and `\t` into what they stand for unless the backslash is itself escaped,
 * then `\\` into `\`, and leaves everything else alone. In particular `\'` stays
 * a backslash and an apostrophe, which is why the migration wrote the
 * apostrophes bare and why StringResourceParityTest refuses `\'` in these files.
 *
 * Mirrored here from the plugin's bytecode rather than approximated by the old
 * Android version, so a string that would show a stray backslash on screen
 * fails the comparison instead of being tidied up by the test.
 */
internal fun String.asComposeWouldLoadIt(): String {
    // The second backslash of every `\\`: an escape starting there is not one.
    val escaped = Regex("""\\\\""").findAll(this).map { it.range.last }.toSet()
    return Regex("""\\u[a-fA-F\d]{4}|\\n|\\t""")
        .replace(this) { match ->
            when {
                match.range.first in escaped -> match.value
                match.value == "\\n" -> "\n"
                match.value == "\\t" -> "\t"
                else -> match.value.substring(2).toInt(16).toChar().toString()
            }
        }
        .replace("\\\\", "\\")
}

/**
 * Where :ui keeps the screens' resources: their strings, and the brand mark the
 * launcher icon script cuts for the welcome screen. See presentation/build.gradle.kts.
 */
internal fun screenResourcesDirectory(): File = File(
    System.getProperty("uiResDir")
        ?: error("uiResDir was not set; see presentation/build.gradle.kts"),
)
