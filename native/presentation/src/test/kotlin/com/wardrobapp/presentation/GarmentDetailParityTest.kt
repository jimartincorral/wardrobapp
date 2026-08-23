package com.wardrobapp.presentation

import com.wardrobapp.data.normalizeGarmentRow
import com.wardrobapp.parity.Parity
import com.wardrobapp.parity.Parity.objects
import com.wardrobapp.parity.Parity.string
import com.wardrobapp.parity.Parity.stringOrNull
import com.wardrobapp.parity.Parity.strings
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What the detail screen shows, against the TypeScript's answer.
 *
 * The corpus starts from raw rows rather than built records, so each case runs
 * the whole path -- normalization and then the view -- and a divergence in
 * either shows up here. Every index the screen could hand over is exercised,
 * including two past the end: a remembered selection can outlive the photo it
 * referred to.
 */
class GarmentDetailParityTest {

    /** Rebuild the row as a SQLite driver would hand it over. */
    private fun rowFrom(json: JsonObject): Map<String, Any?> = json.mapValues { (_, value) ->
        when {
            value is JsonNull -> null
            value is JsonPrimitive && value.isString -> value.content
            value is JsonPrimitive -> value.content.toLongOrNull() ?: value.content.toDouble()
            else -> fail("Unexpected fixture value shape: $value")
        }
    }

    @Test
    fun `matches the TypeScript for every garment and selection`() {
        val cases = Parity.load("garment-detail.jsonl")
        val failures = mutableListOf<String>()

        for (case in cases) {
            val row = rowFrom(case["row"] as JsonObject)
            val selected = case["selected"]!!.jsonPrimitive.content.toInt()
            val expected = case["view"]!!.jsonObject

            val actual = garmentDetail(normalizeGarmentRow(row, ""), selected)
            val where = "${row["id"]} at index $selected"

            fun check(field: String, expectedValue: Any?, actualValue: Any?) {
                if (expectedValue != actualValue) {
                    failures += "$where .$field: expected $expectedValue, got $actualValue"
                }
            }

            check("displayedImage", expected.stringOrNull("displayedImage"), actual.displayedImage)
            check("showsGallery", expected.string("showsGallery") == "true", actual.showsGallery)
            check("selectedIndex", expected.string("selectedIndex").toInt(), actual.selectedIndex)
            check("category", expected.string("category"), actual.category)
            check("subcategories", expected.strings("subcategories"), actual.subcategories)
            check("brand", expected.stringOrNull("brand"), actual.brand)
            check("size", expected.stringOrNull("size"), actual.size)
            check("seasons", expected.strings("seasons"), actual.seasons.map { it.tag })
            check("occasions", expected.strings("occasions"), actual.occasions.map { it.id })
            check("tags", expected.strings("tags"), actual.tags)
            check("isAvailable", expected.string("isAvailable") == "true", actual.isAvailable)
            check("unavailableDate", expected.stringOrNull("unavailableDate"), actual.unavailableDate)
            check("purchaseDate", expected.stringOrNull("purchaseDate"), actual.purchaseDate)
            check(
                "backgroundAction",
                expected.stringOrNull("backgroundAction"),
                actual.backgroundAction?.name?.lowercase(),
            )

            val expectedPalette = expected.objects("palette")
                .map { it.string("hex") to it.stringOrNull("colorKey") }
            check("palette", expectedPalette, actual.palette.map { it.hex to it.colorKey })

            val expectedGallery = expected.objects("gallery").map {
                Triple(it.string("uri"), it.string("selected") == "true", it.string("hasCutout") == "true")
            }
            check(
                "gallery",
                expectedGallery,
                actual.gallery.map { Triple(it.uri, it.selected, it.hasCutout) },
            )
        }

        assertTrue(
            failures.isEmpty(),
            "${failures.size} divergences across ${cases.size} cases:\n" +
                failures.take(15).joinToString("\n"),
        )
    }

    @Test
    fun `the corpus covers the states worth covering`() {
        val cases = Parity.load("garment-detail.jsonl")
        val views = cases.map { it["view"]!!.jsonObject }

        fun covered(what: String, predicate: (JsonObject) -> Boolean) =
            assertTrue(views.any(predicate), "no case in the corpus has $what")

        covered("a garment with no photo") { it["displayedImage"] is JsonNull }
        covered("more than one photo") { it.string("showsGallery") == "true" }
        covered("a cut-out to undo") { it.stringOrNull("backgroundAction") == "undo" }
        covered("a cut-out that replaced its original") { it["backgroundAction"] is JsonNull }
        covered("an unnamed colour") { it.objects("palette").any { p -> p["colorKey"] is JsonNull } }
        covered("no brand") { it["brand"] is JsonNull }
        covered("an unavailable garment") { it.string("isAvailable") == "false" }
        covered("a season tag") { it.strings("seasons").isNotEmpty() }
        covered("custom tags") { it.strings("tags").isNotEmpty() }
        covered("a selection past the end") { it.string("selectedIndex") == "0" }
    }

    @Test
    fun `every colour the picker offers has a name`() {
        // A hex in the picker that this cannot name would show as a raw code on
        // the detail screen -- the failure mode is silent, so it is stated here.
        val duplicates = GARMENT_COLORS.groupBy { it.second.uppercase() }.filterValues { it.size > 1 }
        assertEquals(emptyMap(), duplicates, "two colour keys share a hex")

        for ((key, hex) in GARMENT_COLORS) {
            val named = garmentDetail(
                recordWith(palette = listOf(hex))
            ).palette.single().colorKey
            assertEquals(key, named, "$hex is not named")
        }
    }

    private fun recordWith(palette: List<String>) = normalizeGarmentRow(
        mapOf(
            "id" to "probe",
            "image_uri" to "front.jpg",
            "category" to "tops",
            "color_primary" to palette.first(),
            "color_palette" to palette.joinToString(",", "[\"", "\"]"),
        ),
        "",
    )
}
