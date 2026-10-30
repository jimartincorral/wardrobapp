package com.wardrobapp.presentation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No Java class newer than Android 7 in the code the phone runs but lint does
 * not read.
 *
 * The app's minSdk is 24, Android 7.0. Lint's NewApi check knows that, and
 * reads :app and :ui -- but the pure modules reach the app as plain JVM jars,
 * built against a desktop JDK that has every class Java has ever had, and
 * lint does not look inside them. So a class Android only gained in 8 compiles
 * here, passes every test, passes CI, and fails on the phone. Two did:
 * `java.nio.file.FileSystemException` in the sync's error handling, which
 * crashed a paired phone on Android 7 whenever its sync could not connect, and
 * `java.util.Base64` in the restore of a v1 backup.
 *
 * A list of packages rather than of classes, and only the ones that arrived in
 * Android 8 wholesale: everything in them is out of reach, so naming the
 * package is exact. Desugaring could bring some of them back, and was not
 * chosen: it would add a library to every build to save three imports, and
 * `java.nio.file` is not in the default desugared set anyway.
 */
class AndroidOnlyApisTest {

    /** Each with what to use instead, so a failure says how to fix itself. */
    private val newerThanMinSdk = mapOf(
        "java.nio.file" to "java.io.File",
        "java.util.Base64" to "kotlin.io.encoding.Base64",
        "java.time" to "kotlinx.datetime, or the epoch milliseconds StoredMoment uses",
    )

    @Test
    fun `the modules the phone runs use nothing newer than its minSdk`() {
        val offenders = sourceFiles().flatMap { file ->
            usesIn(file.readText()).map { (line, api) ->
                "${file.name}:$line uses $api -- use ${newerThanMinSdk.getValue(api)}"
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "Android 7 does not have these, and lint does not read these modules:\n  " +
                offenders.joinToString("\n  "),
        )
    }

    @Test
    fun `the check reads the sources it claims to`() {
        // An empty list of files passes the test above as readily as a clean
        // one does, and reads identically.
        val modules = sourceDirs().map { it.path }
        assertTrue(sourceDirs().all { it.isDirectory }, "missing: $modules")
        assertTrue(sourceFiles().any { it.name == "PhoneSync.kt" }, "the sync's sources are not among $modules")
        assertTrue(sourceFiles().any { it.name == "ArchiveRestore.kt" }, "the restore's sources are not among $modules")
    }

    @Test
    fun `the detector finds the shapes it claims to, and not comments`() {
        val found = usesIn(
            """
            import java.nio.file.FileSystemException
            import java.util.Base64
            val today = java.time.LocalDate.now()
            // java.time is fine to mention in a comment
             * and java.util.Base64 in KDoc
            import java.util.Base64Thing
            import java.timer.Something
            """.trimIndent()
        )

        assertEquals(
            listOf(1 to "java.nio.file", 2 to "java.util.Base64", 3 to "java.time"),
            found,
        )
    }

    /** Each line that names one of [newerThanMinSdk] in code, with the package it names. */
    private fun usesIn(source: String): List<Pair<Int, String>> =
        source.lines().withIndex().mapNotNull { (index, line) ->
            val code = line.trimStart()
            if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) return@mapNotNull null
            newerThanMinSdk.keys
                // The package itself, followed by something that ends its name:
                // java.timer is not java.time.
                .firstOrNull { api -> Regex("""\b${Regex.escape(api)}(?![\w])""").containsMatchIn(code) }
                ?.let { index + 1 to it }
        }

    private fun sourceDirs(): List<File> =
        System.getProperty("phoneSourceDirs")
            .split(File.pathSeparator)
            .map(::File)

    private fun sourceFiles(): List<File> =
        sourceDirs().flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
}
