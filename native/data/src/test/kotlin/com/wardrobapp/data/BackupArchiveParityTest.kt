package com.wardrobapp.data

import com.wardrobapp.parity.Parity
import com.wardrobapp.parity.Parity.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Archive validation, against the TypeScript.
 *
 * Compared on the *message* as well as accept-or-reject, because the message is
 * the only thing telling someone whether to update the app or give up on the
 * file. A port that rejected the right archives with the wrong explanation would
 * be a worse app, and comparing booleans would not notice.
 */
class BackupArchiveParityTest {

    private data class Outcome(val ok: Boolean, val message: String?)

    private fun attempt(operation: () -> Unit): Outcome = try {
        operation()
        Outcome(true, null)
    } catch (e: Exception) {
        Outcome(false, e.message)
    }

    private fun expected(case: JsonObject): Outcome {
        val result = case["result"]!!.jsonObject
        val ok = result["ok"]!!.jsonPrimitive.content == "true"
        return Outcome(ok, if (ok) null else result.string("message"))
    }

    private fun JsonObject.int(key: String): Int =
        (this[key] as JsonPrimitive).content.toDouble().toInt()

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as JsonPrimitive).content == "true"

    @Test
    fun `matches the TypeScript on every archive`() {
        val cases = Parity.load("archive-validation.jsonl")
        val failures = mutableListOf<String>()

        for (case in cases) {
            val kind = case.string("kind")
            val expected = expected(case)

            val actual = when (kind) {
                "manifest" -> {
                    val text = case.string("input")
                    attempt { parseArchiveManifest(text) }
                }
                "completeness" -> {
                    val input = case["input"]!!.jsonObject
                    val declared = if (input.containsKey("imageCount")) input.int("imageCount") else null
                    attempt {
                        checkArchiveCompleteness(
                            manifest = ArchiveManifest(version = BACKUP_VERSION, imageCount = declared),
                            hasDatabase = input.bool("hasDatabase"),
                            imageCount = input.int("present"),
                        )
                    }
                }
                "legacy" -> {
                    val input = case["input"]!!.jsonObject
                    attempt { checkLegacyPayload(input.int("version"), input.bool("hasDatabase")) }
                }
                else -> fail("Unknown case kind in fixture: $kind")
            }

            val where = "$kind ${case["input"]}"

            if (expected.ok != actual.ok) {
                failures += "$where: expected ok=${expected.ok}, got ok=${actual.ok} (${actual.message})"
            } else if (!expected.ok && expected.message != actual.message) {
                failures += "$where message:\n      expected: ${expected.message}\n      got:      ${actual.message}"
            }
        }

        assertTrue(
            failures.isEmpty(),
            "${failures.size} of ${cases.size} archives diverged:\n" + failures.take(12).joinToString("\n")
        )
    }

    @Test
    fun `the fixture covers every way an archive can be refused`() {
        val cases = Parity.load("archive-validation.jsonl")
        val messages = cases.mapNotNull { case ->
            val result = case["result"]!!.jsonObject
            if (result["ok"]!!.jsonPrimitive.content == "true") null else result.string("message")
        }

        assertTrue(cases.any { c -> c["result"]!!.jsonObject["ok"]!!.jsonPrimitive.content == "true" },
            "nothing is accepted: the corpus only proves it refuses everything")

        // Each distinct refusal, because they send the reader somewhere
        // different: update the app, give up on the file, or re-export it.
        for (fragment in listOf(
            "is not readable JSON",
            "does not describe a backup",
            "has no version number",
            "made by a newer version",
            "Unsupported backup format",
            "is missing from the archive",
            "the manifest lists",
            "contains no database",
        )) {
            assertTrue(
                messages.any { it.contains(fragment) },
                "no case produces the refusal containing \"$fragment\""
            )
        }
    }

    @Test
    fun `a whole-valued version written as a decimal is accepted`() {
        // JSON has no integers, and JavaScript's Number.isInteger accepts 3.0 --
        // so a manifest written that way is valid there and has to be here too.
        // This is stated directly because it is the kind of thing a corpus
        // regenerated later could quietly stop covering.
        val manifest = parseArchiveManifest("""{"version":3.0}""")
        assertTrue(manifest.version == BACKUP_VERSION)

        val fractional = runCatching { parseArchiveManifest("""{"version":3.5}""") }
        assertTrue(fractional.isFailure, "3.5 is not a format version")
    }
}
