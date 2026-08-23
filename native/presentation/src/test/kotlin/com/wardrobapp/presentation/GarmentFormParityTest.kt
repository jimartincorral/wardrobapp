package com.wardrobapp.presentation

import com.wardrobapp.domain.Season
import com.wardrobapp.parity.Parity
import com.wardrobapp.parity.Parity.double
import com.wardrobapp.parity.Parity.string
import com.wardrobapp.parity.Parity.stringOrNull
import com.wardrobapp.parity.Parity.strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Form transitions, replayed step by step against the TypeScript.
 *
 * The fixture records the whole state after every operation rather than only at
 * the end of a sequence, because two implementations can disagree in the middle
 * and coincide by the finish -- an off-by-one in the selection that a later
 * removal happens to correct, for instance.
 */
class GarmentFormParityTest {

    /** The fixed season rule the fixture used, so neither side consults a table. */
    private val scriptSeasons = mapOf(
        "Parka" to listOf(Season.WINTER),
        "Sundress" to listOf(Season.SUMMER),
    )

    private fun JsonArray.strings(): List<String> = map { it.jsonPrimitive.content }

    private fun apply(state: GarmentFormState, op: String, args: JsonArray): GarmentFormState =
        when (op) {
            "withImage" -> state.withImage(
                args[0].jsonPrimitive.content,
                args.getOrNull(1)?.jsonPrimitive?.content == "true",
            )
            "withoutImageAt" -> state.withoutImageAt(args[0].jsonPrimitive.content.toInt())
            "withImagesReordered" -> state.withImagesReordered(
                args[0].jsonPrimitive.content.toInt(),
                args[1].jsonPrimitive.content.toInt(),
            )
            "withBackgroundRemoved" -> state.withBackgroundRemoved(args[0].jsonPrimitive.content)
            "withSubcategories" -> state.withSubcategories(
                (args[0] as JsonArray).strings(),
            ) { subs -> subs.flatMap { scriptSeasons[it] ?: emptyList() } }
            "withColorToggled" -> state.withColorToggled(args[0].jsonPrimitive.content)
            "withDetectedColor" -> state.withDetectedColor(args[0].jsonPrimitive.content)
            "withImportedPreview" -> state.withImportedPreview(
                (args[0] as JsonArray).strings(),
                if (args[1] is JsonNull) null else args[1].jsonPrimitive.content,
            )
            else -> fail("Unknown form op in fixture: $op")
        }

    @Test
    fun `matches the TypeScript at every step of every sequence`() {
        val cases = Parity.load("form-transitions.jsonl")
        val failures = mutableListOf<String>()

        var currentScript: String? = null
        var state = GarmentFormState().normalized()

        for (case in cases) {
            val script = case.string("script")
            if (script != currentScript) {
                currentScript = script
                state = GarmentFormState().normalized()
            }

            val op = case.string("op")
            val step = case.double("step").toInt()
            state = apply(state, op, case["args"]!!.jsonArray)

            val expected = case["state"]!!.jsonObject
            val where = "'$script' step $step ($op)"

            fun check(field: String, expectedValue: Any?, actualValue: Any?) {
                if (expectedValue != actualValue) {
                    failures += "$where .$field: expected $expectedValue, got $actualValue"
                }
            }

            check("imageUris", expected.strings("imageUris"), state.imageUris)
            check("bgRemovedUris", expected.strings("bgRemovedUris"), state.bgRemovedUris)
            check("selectedImageIndex", expected.double("selectedImageIndex").toInt(), state.selectedImageIndex)
            check("subcategories", expected.strings("subcategories"), state.subcategories)
            check("seasons", expected.strings("seasons"), state.seasons.map { it.tag })
            check("brand", expected.string("brand"), state.brand)
            check("colorPalette", expected.strings("colorPalette"), state.colorPalette)

            val derived = case["derived"]!!.jsonObject
            check("preview", derived.stringOrNull("preview"), state.displayedPreviewUri())
            check("hasOriginal", derived["hasOriginal"]!!.jsonPrimitive.content == "true", state.selectedHasOriginal())

            val expectedGallery = derived["gallery"]!!.jsonArray.map {
                val item = it.jsonObject
                GarmentFormState.GalleryItem(item.string("uri"), item.string("original"))
            }
            check("gallery", expectedGallery, state.galleryItems())
        }

        assertTrue(
            failures.isEmpty(),
            "${failures.size} divergences across ${cases.size} steps:\n" +
                failures.take(20).joinToString("\n")
        )
    }

    @Test
    fun `matches the TypeScript when bringing a state into shape`() {
        // The transition scripts always start already aligned, so the padding and
        // trimming needs its own corpus or it goes unexercised entirely.
        val cases = Parity.load("form-normalization.jsonl")
        val failures = mutableListOf<String>()

        for (case in cases) {
            val input = case["input"]!!.jsonObject
            val expected = case["normalized"]!!.jsonObject

            val state = GarmentFormState(
                imageUris = if (input.containsKey("imageUris")) input.strings("imageUris") else emptyList(),
                bgRemovedUris = if (input.containsKey("bgRemovedUris")) input.strings("bgRemovedUris") else emptyList(),
                brand = if (input.containsKey("brand")) input.string("brand") else "",
                size = if (input.containsKey("size")) input.string("size") else "",
                colorPalette = if (input.containsKey("colorPalette")) {
                    input.strings("colorPalette")
                } else {
                    listOf(GarmentFormState.DEFAULT_COLOR)
                },
            ).normalized()

            if (expected.strings("bgRemovedUris") != state.bgRemovedUris) {
                failures += "input=$input bgRemovedUris: expected ${expected.strings("bgRemovedUris")}, got ${state.bgRemovedUris}"
            }
            if (expected.strings("colorPalette") != state.colorPalette) {
                failures += "input=$input colorPalette: expected ${expected.strings("colorPalette")}, got ${state.colorPalette}"
            }
            if (expected.strings("imageUris") != state.imageUris) {
                failures += "input=$input imageUris: expected ${expected.strings("imageUris")}, got ${state.imageUris}"
            }
        }

        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `the normalization fixture actually gives it work to do`() {
        val cases = Parity.load("form-normalization.jsonl")

        assertTrue(
            cases.any { case ->
                val input = case["input"]!!.jsonObject
                val given = if (input.containsKey("bgRemovedUris")) input.strings("bgRemovedUris").size else 0
                val photos = if (input.containsKey("imageUris")) input.strings("imageUris").size else 0
                given < photos
            },
            "no case needs padding"
        )
        assertTrue(
            cases.any { case ->
                val input = case["input"]!!.jsonObject
                val given = if (input.containsKey("bgRemovedUris")) input.strings("bgRemovedUris").size else 0
                val photos = if (input.containsKey("imageUris")) input.strings("imageUris").size else 0
                given > photos
            },
            "no case needs trimming"
        )
        assertTrue(
            cases.any { case ->
                val input = case["input"]!!.jsonObject
                input.containsKey("colorPalette") && input.strings("colorPalette").isEmpty()
            },
            "no case needs the palette default"
        )
    }

    @Test
    fun `matches the TypeScript on brand suggestions`() {
        val cases = Parity.load("brand-suggestions.jsonl")
        val failures = mutableListOf<String>()

        for (case in cases) {
            val known = case.strings("known")
            val typed = case.string("typed")
            val expected = case.strings("suggestions")
            val actual = brandSuggestions(known, typed)

            if (expected != actual) {
                failures += "brandSuggestions($known, '$typed'): expected $expected, got $actual"
            }
        }

        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `the fixture exercises the transitions that have rules in them`() {
        val cases = Parity.load("form-transitions.jsonl")
        val ops = cases.map { it.string("op") }.toSet()

        for (op in listOf(
            "withImage", "withoutImageAt", "withImagesReordered", "withBackgroundRemoved",
            "withSubcategories", "withColorToggled", "withDetectedColor", "withImportedPreview",
        )) {
            assertTrue(ops.contains(op), "no step exercises $op")
        }

        // A replacement, which is where a stale cut-out would survive.
        assertTrue(
            cases.any { it.string("op") == "withImage" && it["args"]!!.jsonArray.size == 2 },
            "no photo replacement: the stale-cut-out case is untested"
        )
        // A second import, which is where a typed brand must be kept.
        assertTrue(
            cases.count { it.string("op") == "withImportedPreview" } >= 2,
            "only one import: keeping an already-typed brand is untested"
        )
        // A cut-out recorded, so the preview and gallery differ from the photos.
        assertTrue(
            cases.any { it["derived"]!!.jsonObject.let { d -> d.stringOrNull("preview")?.contains("nobg") == true } },
            "no state where the preview shows a cut-out"
        )
        // hasOriginal must be observed both ways round, or the rule that a
        // cut-out-only photo has nothing to undo to is never checked.
        val hasOriginalValues = cases.map {
            it["derived"]!!.jsonObject["hasOriginal"]!!.jsonPrimitive.content
        }.toSet()
        assertTrue(
            hasOriginalValues.containsAll(setOf("true", "false")),
            "hasOriginal is only ever $hasOriginalValues: the cut-out-only case is untested"
        )
    }

    @Test
    fun `the brand fixture covers an empty field and an exact match`() {
        val cases = Parity.load("brand-suggestions.jsonl")

        assertTrue(cases.any { it.string("typed").isBlank() }, "no blank input")
        assertTrue(
            cases.any { case ->
                val typed = case.string("typed").trim().lowercase()
                typed.isNotEmpty() && case.strings("known").any { it.lowercase() == typed }
            },
            "no exact match: dropping what the user already typed is untested"
        )
        assertTrue(
            cases.any { it.strings("suggestions").size == GarmentFormState.BRAND_SUGGESTION_LIMIT },
            "the cap is never reached"
        )
    }
}
