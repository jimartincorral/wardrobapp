package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import com.wardrobapp.parity.Parity
import com.wardrobapp.parity.Parity.string
import com.wardrobapp.parity.Parity.stringOrNull
import com.wardrobapp.parity.Parity.strings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Wardrobe filtering and ordering, against the TypeScript's answer for every
 * filter combination the screens can produce.
 *
 * The wardrobe includes a garment with no timestamp -- the shape an install
 * upgraded through the ALTER path can hold, which used to make the whole list
 * disappear because the sort dereferenced it and the caller swallowed the error.
 */
class GarmentFilteringParityTest {

    private fun loadWardrobe(): List<GarmentRecord> {
        val stream = javaClass.getResourceAsStream("/parity/filtering-wardrobe.json")
            ?: fail("Missing filtering-wardrobe.json. Generate it with: npm run parity:dump")

        val array = Json.parseToJsonElement(stream.bufferedReader().readText()) as JsonArray

        return array.map { element ->
            val g = element.jsonObject
            GarmentRecord(
                id = g.string("id"),
                imageUri = g.string("image_uri"),
                imageUriNoBg = g.stringOrNull("image_uri_nobg"),
                imageUris = g.strings("image_uris"),
                imageUrisNoBg = g.strings("image_uris_nobg"),
                category = g.string("category"),
                subcategory = g.stringOrNull("subcategory"),
                subcategories = g.strings("subcategories"),
                tags = g.strings("tags"),
                brand = g.stringOrNull("brand"),
                colorPrimary = g.string("color_primary"),
                colorSecondary = g.stringOrNull("color_secondary"),
                colorPalette = g.strings("color_palette"),
                size = g.stringOrNull("size"),
                purchaseDate = g.stringOrNull("purchase_date"),
                isAvailable = true,
                unavailableDate = g.stringOrNull("unavailable_date"),
                createdAt = g.stringOrNull("created_at"),
                updatedAt = g.stringOrNull("updated_at"),
            )
        }
    }

    private fun filterFrom(json: JsonObject) = GarmentFilter(
        subcategory = json.stringOrNull2("subcategory"),
        season = json.stringOrNull2("season")?.let {
            Season.fromTag(it) ?: fail("Unknown season in fixture: $it")
        },
        occasion = json.stringOrNull2("occasion")?.let {
            Occasion.fromId(it) ?: fail("Unknown occasion in fixture: $it")
        },
        brand = json.stringOrNull2("brand"),
        size = json.stringOrNull2("size"),
        color = json.stringOrNull2("color"),
    )

    /** Absent keys are normal here: a filter only carries what was set. */
    private fun JsonObject.stringOrNull2(key: String): String? =
        if (containsKey(key)) stringOrNull(key) else null

    @Test
    fun `matches the TypeScript for every filter and ordering`() {
        val wardrobe = loadWardrobe()
        val cases = Parity.load("garment-filtering.jsonl")
        val failures = mutableListOf<String>()

        for (case in cases) {
            val filter = filterFrom(case["filter"]!!.jsonObject)
            val sort = when (val s = case.string("sort")) {
                "newest" -> GarmentSort.NEWEST
                "oldest" -> GarmentSort.OLDEST
                else -> fail("Unknown sort in fixture: $s")
            }

            val expected = case.strings("ids")
            val actual = wardrobe.filterBy(filter).orderedBy(sort).map { it.id }

            if (expected != actual) {
                failures += "filter=$filter sort=$sort: expected $expected, got $actual"
            }
        }

        assertTrue(
            failures.isEmpty(),
            "${failures.size} of ${cases.size} combinations diverged:\n" +
                failures.take(20).joinToString("\n")
        )
    }

    @Test
    fun `the fixture exercises each filter and a garment with no timestamp`() {
        val wardrobe = loadWardrobe()
        val cases = Parity.load("garment-filtering.jsonl")

        assertTrue(cases.size >= 30, "expected a broad corpus, got ${cases.size}")
        assertTrue(
            wardrobe.any { it.createdAt == null },
            "no garment without a timestamp: the case that emptied the list is untested"
        )

        for (key in listOf("subcategory", "season", "occasion", "brand", "size", "color")) {
            assertTrue(
                cases.any { it["filter"]!!.jsonObject.containsKey(key) },
                "no case filters by $key"
            )
        }

        // Results must actually vary, or agreement means nothing.
        assertTrue(
            cases.map { it.strings("ids") }.distinct().size >= 8,
            "the filters barely change the result set"
        )
        assertTrue(cases.any { it.strings("ids").isEmpty() }, "no filter excludes everything")
        assertTrue(cases.any { it.strings("ids").size >= 5 }, "no filter keeps most of the wardrobe")
    }
}
