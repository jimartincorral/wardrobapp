package com.wardrobapp.data

import com.wardrobapp.parity.Parity
import com.wardrobapp.parity.Parity.string
import com.wardrobapp.parity.Parity.stringOrNull
import com.wardrobapp.parity.Parity.strings
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Row normalization -- the most compatibility-critical code in the port, since
 * it decides whether an existing wardrobe reads correctly.
 *
 * The corpus is rows in the shapes the table actually holds: JSON list columns
 * from current builds, comma-separated ones from much older builds, missing
 * columns, blank and whitespace values, case-duplicate entries, legacy absolute
 * photo paths, and `is_available` as the string "0" -- which is truthy in JS, so
 * the garment is available.
 */
class GarmentRowsParityTest {

    /**
     * Rebuild the row as a SQLite driver would hand it over.
     *
     * JSON has no integer/real distinction, so a numeric fixture value becomes a
     * Long when it has no fractional part -- which is what the driver returns for
     * an INTEGER column, and what `is_available` has to cope with.
     */
    private fun rowFrom(json: JsonObject): Map<String, Any?> = json.mapValues { (_, value) ->
        when {
            value is JsonNull -> null
            value is JsonPrimitive && value.isString -> value.content
            value is JsonPrimitive && value.content == "true" -> true
            value is JsonPrimitive && value.content == "false" -> false
            value is JsonPrimitive -> value.content.toLongOrNull() ?: value.content.toDouble()
            else -> fail("Unexpected fixture value shape: $value")
        }
    }

    @Test
    fun `matches the TypeScript implementation for every row shape`() {
        val cases = Parity.load("garment-rows.jsonl")
        val failures = mutableListOf<String>()

        for ((index, case) in cases.withIndex()) {
            val row = rowFrom(case["row"] as JsonObject)
            val directory = case.string("directory")
            val expected = case["normalized"] as JsonObject
            val actual = normalizeGarmentRow(row, directory)
            val where = "row ${expected.string("id")} (case $index, directory '$directory')"

            fun check(field: String, expectedValue: Any?, actualValue: Any?) {
                if (expectedValue != actualValue) {
                    failures += "$where .$field: expected $expectedValue, got $actualValue"
                }
            }

            check("id", expected.string("id"), actual.id)
            check("image_uri", expected.string("image_uri"), actual.imageUri)
            check("image_uri_nobg", expected.stringOrNull("image_uri_nobg"), actual.imageUriNoBg)
            check("image_uris", expected.strings("image_uris"), actual.imageUris)
            check("image_uris_nobg", expected.strings("image_uris_nobg"), actual.imageUrisNoBg)
            check("category", expected.string("category"), actual.category)
            check("subcategory", expected.stringOrNull("subcategory"), actual.subcategory)
            check("subcategories", expected.strings("subcategories"), actual.subcategories)
            check("tags", expected.strings("tags"), actual.tags)
            check("brand", expected.stringOrNull("brand"), actual.brand)
            check("color_primary", expected.string("color_primary"), actual.colorPrimary)
            check("color_secondary", expected.stringOrNull("color_secondary"), actual.colorSecondary)
            check("color_palette", expected.strings("color_palette"), actual.colorPalette)
            check("size", expected.stringOrNull("size"), actual.size)
            check("purchase_date", expected.stringOrNull("purchase_date"), actual.purchaseDate)
            check("unavailable_date", expected.stringOrNull("unavailable_date"), actual.unavailableDate)
            check("created_at", expected.stringOrNull("created_at"), actual.createdAt)
            check("updated_at", expected.stringOrNull("updated_at"), actual.updatedAt)

            val expectedAvailable = expected["is_available"].toString() == "true"
            check("is_available", expectedAvailable, actual.isAvailable)
        }

        assertTrue(
            failures.isEmpty(),
            "${failures.size} divergences across ${cases.size} rows:\n" +
                failures.take(25).joinToString("\n")
        )
    }

    @Test
    fun `the fixture spans the row shapes that actually exist`() {
        val cases = Parity.load("garment-rows.jsonl")
        val rows = cases.map { it["row"] as JsonObject }

        assertTrue(cases.size >= 30, "expected a broad corpus, got ${cases.size}")

        assertTrue(
            rows.any { row -> row["tags"]?.let { it is JsonPrimitive && it.content.startsWith("[") } == true },
            "no JSON list column in the corpus"
        )
        assertTrue(
            rows.any { row ->
                row["tags"]?.let { it is JsonPrimitive && it.content.contains(",") && !it.content.startsWith("[") } == true
            },
            "no comma-separated list column: the oldest row shape is untested"
        )
        assertTrue(rows.any { !it.containsKey("tags") }, "no row with columns missing")
        assertTrue(
            rows.any { row -> row["is_available"]?.let { it is JsonPrimitive && it.isString } == true },
            "is_available is never a string: the JS truthiness path is untested"
        )
        assertTrue(
            rows.any { row ->
                row["image_uri"]?.let { it is JsonPrimitive && it.content.startsWith("file:///") } == true
            },
            "no legacy absolute photo path in the corpus"
        )
        // The no-background gap matters: its indices line up with the photo list.
        assertTrue(
            cases.any { it["normalized"].let { n -> (n as JsonObject).strings("image_uris_nobg").any { u -> u.isEmpty() } } },
            "no preserved gap in image_uris_nobg: the preserveEmpty path is untested"
        )
    }
}
