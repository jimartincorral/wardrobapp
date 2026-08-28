package com.wardrobapp.presentation

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every XML file :app ships, parsed.
 *
 * Here for the same reason [StringResourceParityTest] is: :app needs the Android
 * SDK and this module does not, so without this the first thing to read these
 * files is a CI runner several minutes away.
 *
 * The failure worth catching is narrow and has now happened twice. A comment
 * containing a double hyphen is not a comment -- XML forbids the sequence inside
 * one -- and the manifest merger's answer is `Error parsing AndroidManifest.xml`
 * with no line, no column and no hint. It is easy to write by accident in a
 * codebase whose comments use dashes for asides, and it stops the build outright
 * rather than failing a check, because `processDebugMainManifest` runs before
 * almost everything.
 *
 * Nothing here validates Android's schema; that is `:app:lint`'s job and needs the
 * SDK. This asks only whether the file is XML at all, which is the question that
 * has actually gone wrong.
 */
class XmlWellFormedTest {

    @Test
    fun `the manifest is well-formed XML`() {
        val manifest = File(
            System.getProperty("appManifest")
                ?: error("appManifest was not set; see presentation/build.gradle.kts"),
        )

        assertTrue(manifest.isFile, "no manifest found at ${manifest.path}")
        parseOrFail(manifest)
    }

    @Test
    fun `every resource file is well-formed XML`() {
        val resDir = File(
            System.getProperty("appResDir")
                ?: error("appResDir was not set; see presentation/build.gradle.kts"),
        )

        val files = resDir.walkTopDown().filter { it.isFile && it.extension == "xml" }.toList()

        assertTrue(files.isNotEmpty(), "no XML resources found under ${resDir.path}")
        for (file in files) parseOrFail(file)
    }

    /**
     * Parse, and say which file and where when it will not.
     *
     * The message is the point: the merger's own error names the file and stops,
     * and a test that only said "failed" would be no better placed than CI.
     */
    private fun parseOrFail(file: File) {
        try {
            DocumentBuilderFactory.newInstance()
                // No network, and no DTD fetching: these files are read for their
                // shape, and a parser that reaches out is a test that fails when a
                // server is down.
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(file)
        } catch (error: Exception) {
            fail("${file.name} is not well-formed XML: ${error.message}")
        }
    }
}
