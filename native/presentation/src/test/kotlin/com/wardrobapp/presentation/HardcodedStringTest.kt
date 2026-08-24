package com.wardrobapp.presentation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No user-facing text left in Kotlin.
 *
 * The point of this one is completeness rather than correctness: extracting 135
 * strings across ten screens is exactly the job where one gets missed, and a
 * missed string is invisible in English -- it reads perfectly until someone
 * switches to Spanish and one label stays put.
 *
 * Here for the same reason as [StringResourceParityTest]: :app cannot be compiled
 * on a machine with no Android SDK, so a check that reads its sources is the only
 * one available before CI. It looks at the call sites where text reaches a person
 * -- `Text(...)`, `contentDescription`, and the message fields a ViewModel puts on
 * its state -- and not at every literal, because plenty of literals are route
 * names, SQL, or animation labels.
 *
 * [filesStillToConvert] is the work not yet done, and shrinks to nothing. A file
 * listed there is exempt; a file not listed has to be clean. That way the test
 * says which screens are finished instead of failing until every one of them is.
 */
class HardcodedStringTest {

    /**
     * Screens whose text has not been moved to resources yet.
     *
     * Every entry is a promise, not a decision. Empty is the finished state.
     */
    private val filesStillToConvert = setOf(
        "AndroidBackgroundRemover.kt",
        "AndroidPhotoStore.kt",
        "AnalyticsScreen.kt",
        "GarmentDetailScreen.kt",
        "GarmentDetailViewModel.kt",
        "GarmentFormScreen.kt",
        "GarmentFormViewModel.kt",
        "SettingsScreen.kt",
        "StatisticsScreen.kt",
    )

    @Test
    fun `converted screens have no user-facing literal left`() {
        val offenders = sourceFiles()
            .filter { it.name !in filesStillToConvert }
            .flatMap { file -> userFacingLiterals(file.readText()).map { "${file.name}: \"$it\"" } }

        assertTrue(
            offenders.isEmpty(),
            "text that would not translate:\n  " + offenders.joinToString("\n  "),
        )
    }

    @Test
    fun `the list of unconverted screens is honest`() {
        // A file that has been converted but left on the list would exempt itself
        // from the check above for good, which is how this kind of allowlist stops
        // meaning anything.
        val names = sourceFiles().map { it.name }.toSet()
        assertEquals(emptySet(), filesStillToConvert - names, "no such file")

        val alreadyClean = sourceFiles()
            .filter { it.name in filesStillToConvert }
            .filter { userFacingLiterals(it.readText()).isEmpty() }
            .map { it.name }

        assertEquals(emptyList(), alreadyClean, "these are done -- take them off the list")
    }

    @Test
    fun `the detector finds the shapes it claims to`() {
        // Otherwise "no offenders" could mean the patterns match nothing at all,
        // which is the same result as success and reads identically.
        val found = userFacingLiterals(
            """
            Text("plain")
            Text(
                "wrapped onto its own line",
                style = x,
            )
            Icon(x, contentDescription = "described")
            state.copy(error = "a message")
            it.copy(error = e.message ?: "a fallback message")
            throw IOException("thrown to be read")
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "plain",
                "wrapped onto its own line",
                "described",
                "a message",
                "a fallback message",
                "thrown to be read",
            ),
            found.sortedBy { found.indexOf(it) },
        )
    }

    @Test
    fun `the detector ignores what is not shown to anyone`() {
        val found = userFacingLiterals(
            """
            // Text("in a comment")
            /* Text("in a block comment") */
            navigate("garment/${'$'}id")
            Text(stringResource(R.string.already_done))
            Icon(x, contentDescription = null)
            driver.query("SELECT 1")
            animateFloatAsState(targetValue = f, label = "bar")
            """.trimIndent()
        )

        assertEquals(emptyList(), found)
    }

    private fun sourceFiles(): List<File> {
        val dir = System.getProperty("appSourceDir")
            ?: error("appSourceDir was not set; see presentation/build.gradle.kts")
        val files = File(dir).listFiles { f: File -> f.name.endsWith(".kt") }?.sorted()

        assertTrue(!files.isNullOrEmpty(), "no Kotlin sources found under $dir")
        return files!!
    }

    private fun userFacingLiterals(source: String): List<String> {
        val code = source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\n]*"), "")

        val patterns = listOf(
            // Text("..."), including the form where the text is on its own line.
            Regex("""\bText\(\s*"((?:[^"\\]|\\.)*)"""),
            Regex("""contentDescription\s*=\s*"((?:[^"\\]|\\.)*)""""),
            // What a ViewModel hands to a screen to show.
            Regex("""\b(?:error|actionError|message)\s*=\s*(?:[\w.]+\s*\?:\s*)?"((?:[^"\\]|\\.)*)""""),
            // Thrown text. :app's own photo and background classes throw
            // sentences written to be read, and the ViewModels show them through
            // `e.message ?: fallback` -- so a screen can be free of literals while
            // still putting English in front of a Spanish reader. Leaving these
            // out made the check call two files clean that were not.
            Regex("""(?:IOException|IllegalStateException|IllegalArgumentException|Exception)\(\s*"((?:[^"\\]|\\.)*)""""),
            Regex("""\berror\(\s*"((?:[^"\\]|\\.)*)""""),
        )

        return patterns.flatMap { pattern ->
            pattern.findAll(code).map { it.groupValues[1] }
        }.filter { it.length > 1 }
    }
}
