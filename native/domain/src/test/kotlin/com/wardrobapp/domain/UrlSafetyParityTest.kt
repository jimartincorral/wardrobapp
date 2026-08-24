package com.wardrobapp.domain

import com.wardrobapp.parity.Parity
import com.wardrobapp.parity.Parity.string
import com.wardrobapp.parity.Parity.stringOrNull
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The port refuses and rewrites the same addresses as the TypeScript.
 *
 * Three questions, all from one fixture:
 *
 *  - `host`: is this hostname on the public internet? Every private range
 *    appears with the address on either side of its edge, so a range written one
 *    off is caught rather than reasoned about.
 *  - `normalize`: what does an accepted address become, and what does a refused
 *    one say? Both halves matter -- the string is what gets fetched and stored,
 *    and the message is the only thing telling someone why nothing happened.
 *  - `redirect`: is where the request ended up still allowed?
 *
 * Messages are compared rather than just accept-or-reject, which is what makes
 * this a parity test instead of a smoke test: the port could refuse everything
 * and pass a check that only asked whether it refused.
 */
class UrlSafetyParityTest {

    @Test
    fun `every hostname is categorised the same way`() {
        val failures = mutableListOf<String>()

        for (case in casesOfKind("host")) {
            val host = case.string("input")
            val expected = case["result"]!!.jsonPrimitive.boolean
            val actual = isPubliclyRoutableHost(host)

            if (expected != actual) {
                failures += "isPubliclyRoutableHost(\"$host\"): expected $expected, got $actual"
            }
        }

        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `every address normalizes or is refused the same way`() {
        val failures = mutableListOf<String>()

        for (case in casesOfKind("normalize")) {
            val input = case.string("input")
            val result = case["result"]!!.jsonObject
            val expected = if (result["ok"]!!.jsonPrimitive.boolean) {
                Outcome.Accepted(result.string("url"))
            } else {
                Outcome.Refused(result.string("message"))
            }

            val actual = try {
                Outcome.Accepted(safeImportUrl(input))
            } catch (error: UnsafeUrlException) {
                Outcome.Refused(error.message ?: "")
            }

            if (expected != actual) {
                failures += "safeImportUrl(${quote(input)}): expected $expected, got $actual"
            }
        }

        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `every redirect is allowed or refused the same way`() {
        val failures = mutableListOf<String>()

        for (case in casesOfKind("redirect")) {
            val input = case["input"]!!.jsonObject
            val finalUrl = input.stringOrNull("finalUrl")
            val requested = input.string("requested")
            val result = case["result"]!!.jsonObject
            val expected = if (result["ok"]!!.jsonPrimitive.boolean) {
                Outcome.Accepted("")
            } else {
                Outcome.Refused(result.string("message"))
            }

            val actual = try {
                checkFetchedUrl(finalUrl, requested)
                Outcome.Accepted("")
            } catch (error: UnsafeUrlException) {
                Outcome.Refused(error.message ?: "")
            }

            if (expected != actual) {
                failures += "checkFetchedUrl(${quote(finalUrl)}, ${quote(requested)}): " +
                    "expected $expected, got $actual"
            }
        }

        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `the corpus still covers what it was built to cover`() {
        // Otherwise a fixture regenerated from a shrunken corpus would pass
        // every check above while testing a fraction of what it used to.
        val hosts = casesOfKind("host")
        val normalizations = casesOfKind("normalize")
        val redirects = casesOfKind("redirect")

        assertTrue(hosts.size >= 80, "only ${hosts.size} hostnames")
        assertTrue(normalizations.size >= 60, "only ${normalizations.size} addresses")
        assertTrue(redirects.size >= 10, "only ${redirects.size} redirects")

        assertTrue(
            hosts.any { it["result"]!!.jsonPrimitive.boolean },
            "no hostname in the corpus is allowed, so nothing proves one can be",
        )
        assertTrue(
            normalizations.any { it["result"]!!.jsonObject["ok"]!!.jsonPrimitive.boolean },
            "no address in the corpus is accepted",
        )
        assertTrue(
            normalizations.any { !it["result"]!!.jsonObject["ok"]!!.jsonPrimitive.boolean },
            "no address in the corpus is refused",
        )
    }

    /**
     * What a call came back with.
     *
     * A type rather than a pair of nullable strings so that "accepted, and the URL
     * is empty" cannot be confused with "refused, and the message is empty".
     */
    private sealed interface Outcome {
        data class Accepted(val url: String) : Outcome
        data class Refused(val message: String) : Outcome
    }

    private fun casesOfKind(kind: String) =
        Parity.load("url-safety.jsonl").filter { it.string("kind") == kind }

    /** Quoted, so a failure about a trailing space is readable. */
    private fun quote(value: String?): String = value?.let { "\"$it\"" } ?: "null"
}
