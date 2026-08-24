package com.wardrobapp.domain

import com.wardrobapp.parity.Parity
import com.wardrobapp.parity.Parity.string
import com.wardrobapp.parity.Parity.stringOrNull
import com.wardrobapp.parity.Parity.strings
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The port reads the same garment out of the same page.
 *
 * Every case is a whole page and the whole record extracted from it, so a
 * divergence anywhere -- a title falling back one source too early, an image
 * resolved against the wrong directory, a logo let through, the parser label --
 * shows up here rather than being averaged away.
 *
 * The images are compared *in order*. Order is not cosmetic: the form fills its
 * gallery from this list and the first image becomes the garment's photo, so a
 * port that found the same images in a different order would put a different
 * picture on the card.
 */
class GarmentImportParityTest {

    @Test
    fun `every page extracts the same record`() {
        val failures = mutableListOf<String>()

        for (case in Parity.load("garment-import.jsonl")) {
            val name = case.string("name")
            val html = case.string("html")
            val url = case.string("url")
            val expected = case["result"]!!.jsonObject

            if (!expected["ok"]!!.jsonPrimitive.boolean) {
                // A page whose own address is refused: the extractor never gets
                // to say anything about it.
                val message = expected.string("message")
                val thrown = try {
                    extractGarmentImportDataFromHtml(html, url)
                    null
                } catch (error: UnsafeUrlException) {
                    error.message
                }

                if (thrown != message) {
                    failures += "$name: expected refusal \"$message\", got ${thrown?.let { "\"$it\"" }}"
                }
                continue
            }

            val data = expected["data"]!!.jsonObject
            val actual = try {
                extractGarmentImportDataFromHtml(html, url)
            } catch (error: UnsafeUrlException) {
                failures += "$name: refused with \"${error.message}\", expected a record"
                continue
            }

            compare(name, "sourceUrl", data.string("sourceUrl"), actual.sourceUrl, failures)
            compare(name, "title", data.stringOrNull("title"), actual.title, failures)
            compare(name, "brand", data.stringOrNull("brand"), actual.brand, failures)
            compare(name, "parser", data.string("parser"), actual.parser.fixtureName(), failures)
            compare(name, "imageUrls", data.strings("imageUrls"), actual.imageUrls, failures)
            compare(
                name,
                "warnings",
                data.strings("warnings"),
                actual.warnings.map { it.englishMessage() },
                failures,
            )
        }

        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `the corpus exercises every parser and both kinds of refusal`() {
        // A fixture that had lost its JSON-LD pages would still pass the check
        // above while testing a third of the extractor.
        val cases = Parity.load("garment-import.jsonl")
        val parsers = cases
            .mapNotNull { it["result"]!!.jsonObject["data"]?.jsonObject?.string("parser") }
            .toSet()

        for (expected in listOf("open-graph", "json-ld", "html-images", "mixed", "none")) {
            assertTrue(expected in parsers, "no page in the corpus yields $expected")
        }

        assertTrue(
            cases.any { case ->
                case["result"]!!.jsonObject["data"]
                    ?.jsonObject
                    ?.strings("warnings")
                    ?.isNotEmpty() == true
            },
            "no page in the corpus produces a warning",
        )
        assertTrue(
            cases.any { !it["result"]!!.jsonObject["ok"]!!.jsonPrimitive.boolean },
            "no page in the corpus is refused outright",
        )
    }

    @Test
    fun `an image the page points at the local network survives extraction`() {
        // Deliberate, and worth stating: the extractor reports what the page
        // said, and the refusal happens where the fetching does. A port that
        // filtered here would look safer and would have moved a decision out of
        // the place that is tested for making it.
        val page = """<img src="http://192.168.1.1/cam.jpg" />"""
        val extracted = extractGarmentImportDataFromHtml(page, "https://example.com/p")

        assertTrue("http://192.168.1.1/cam.jpg" in extracted.imageUrls)
    }

    private fun <T> compare(
        case: String,
        field: String,
        expected: T,
        actual: T,
        failures: MutableList<String>,
    ) {
        if (expected != actual) {
            failures += "$case: $field expected $expected, got $actual"
        }
    }

    /** The names the TypeScript uses for its parser labels. */
    private fun ImportParser.fixtureName(): String = when (this) {
        ImportParser.OPEN_GRAPH -> "open-graph"
        ImportParser.JSON_LD -> "json-ld"
        ImportParser.HTML_IMAGES -> "html-images"
        ImportParser.MIXED -> "mixed"
        ImportParser.NONE -> "none"
    }
}
