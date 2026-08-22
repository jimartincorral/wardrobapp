package com.wardrobapp.data

import com.wardrobapp.parity.Parity
import com.wardrobapp.parity.Parity.string
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Photo-reference handling, against every answer the TypeScript gives for the
 * shapes the app has written or received: bare filenames, absolute paths from
 * older installs, SAF documents, remote URLs and inline data.
 */
class ImagePathsParityTest {

    @Test
    fun `matches the TypeScript implementation for every reference and directory`() {
        val cases = Parity.load("image-paths.jsonl")
        val failures = mutableListOf<String>()

        for (case in cases) {
            val ref = case.string("ref")
            val directory = case.string("directory")

            val expectedStored = case.string("stored")
            val actualStored = toStoredImageRef(ref)
            if (expectedStored != actualStored) {
                failures += "toStoredImageRef('$ref'): expected '$expectedStored', got '$actualStored'"
            }

            val expectedResolved = case.string("resolved")
            val actualResolved = resolveImageRef(ref, directory)
            if (expectedResolved != actualResolved) {
                failures += "resolveImageRef('$ref', '$directory'): expected '$expectedResolved', got '$actualResolved'"
            }

            val expectedLegacy = case["legacy"].toString() == "true"
            val actualLegacy = isLegacyAbsoluteImageRef(ref)
            if (expectedLegacy != actualLegacy) {
                failures += "isLegacyAbsoluteImageRef('$ref'): expected $expectedLegacy, got $actualLegacy"
            }
        }

        assertTrue(failures.isEmpty(), "${failures.size} divergences:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `the fixture covers the references that must not be rewritten`() {
        val cases = Parity.load("image-paths.jsonl")
        val refs = cases.map { it.string("ref") }

        assertTrue(refs.any { it.startsWith("content://") }, "no SAF document in the corpus")
        assertTrue(refs.any { it.startsWith("https://") }, "no remote URL in the corpus")
        assertTrue(refs.any { it.startsWith("data:") }, "no inline data in the corpus")
        assertTrue(refs.any { it.startsWith("file:///") }, "no legacy absolute path in the corpus")
        assertTrue(
            refs.any { it.startsWith("CONTENT://") || it.startsWith("HTTP://") },
            "no uppercase scheme in the corpus: the case-insensitive match is untested"
        )
        assertTrue(cases.any { it.string("directory").isEmpty() }, "the empty-directory path is untested")
    }
}
