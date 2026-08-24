package com.wardrobapp.presentation

import com.wardrobapp.parity.Parity
import com.wardrobapp.parity.Parity.double
import com.wardrobapp.parity.Parity.objects
import com.wardrobapp.parity.Parity.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The statistics bars, against the TypeScript.
 *
 * Shapes rather than realistic wardrobes: the arithmetic is what is being
 * compared, so the interesting inputs are charts that dwarf each other, a
 * subcategory group far smaller than its category, counts of zero, and colours
 * stored in both cases.
 */
class StatisticsViewParityTest {

    @Test
    fun `it agrees with the TypeScript`() {
        val failures = mutableListOf<String>()

        for (case in Parity.load("statistics-view.jsonl")) {
            val name = case.string("name")
            val input = case.getValue("input").jsonObject
            val expected = case.getValue("view").jsonObject

            val actual = statisticsView(
                total = input.double("total").toLong(),
                categories = distributions(input, "categories"),
                colors = distributions(input, "colors"),
                brands = distributions(input, "brands"),
                subcategories = input.getValue("subcategories").jsonObject
                    .mapValues { (_, value) ->
                        value.jsonArray.map {
                            Distribution(
                                key = it.jsonObject.string("key"),
                                count = it.jsonObject.double("count").toLong(),
                            )
                        }
                    },
                brandSort = if (input.string("brandSort") == "alpha") {
                    BrandSort.ALPHA
                } else {
                    BrandSort.COUNT
                },
            )

            if (actual.total != expected.double("total").toLong()) {
                failures += "$name: total expected ${expected.double("total")}, got ${actual.total}"
            }
            if (actual.isEmpty != expected.getValue("isEmpty").jsonPrimitive.boolean) {
                failures += "$name: isEmpty disagreed"
            }
            for (field in listOf("distinctCategories", "distinctColors", "distinctBrands")) {
                val want = expected.double(field).toInt()
                val got = when (field) {
                    "distinctCategories" -> actual.distinctCategories
                    "distinctColors" -> actual.distinctColors
                    else -> actual.distinctBrands
                }
                if (got != want) failures += "$name: $field expected $want, got $got"
            }

            compareBars(name, "categories", expected.objects("categories"), actual.categories, failures)
            compareBars(name, "brands", expected.objects("brands"), actual.brands, failures)

            val expectedColors = expected.objects("colors")
            if (expectedColors.size != actual.colors.size) {
                failures += "$name: colors expected ${expectedColors.size} bars, got ${actual.colors.size}"
            } else {
                for ((i, want) in expectedColors.withIndex()) {
                    val got = actual.colors[i]
                    if (got.key != want.string("key")) {
                        failures += "$name: colors[$i] key expected ${want.string("key")}, got ${got.key}"
                    }
                    if (!Parity.sameNumber(got.fraction, want.double("fraction"))) {
                        failures += "$name: colors[$i] fraction expected ${want.double("fraction")}, got ${got.fraction}"
                    }
                    if (got.swatch != want.string("swatch")) {
                        failures += "$name: colors[$i] swatch expected ${want.string("swatch")}, got ${got.swatch}"
                    }
                }
            }

            // The most interesting part: each group is scaled against its own
            // largest bar, and its keys carry the category prefix.
            val expectedSubs = expected.getValue("subcategories").jsonObject
            if (expectedSubs.keys != actual.subcategories.keys) {
                failures += "$name: subcategory groups expected ${expectedSubs.keys}, got ${actual.subcategories.keys}"
            } else {
                for ((category, wanted) in expectedSubs) {
                    compareBars(
                        name = "$name/$category",
                        field = "subcategories",
                        expected = wanted.jsonArray.map { it.jsonObject },
                        actual = actual.subcategories.getValue(category),
                        failures = failures,
                    )
                }
            }
        }

        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private fun distributions(input: JsonObject, key: String): List<Distribution> =
        input.objects(key).map {
            Distribution(key = it.string("key"), count = it.double("count").toLong())
        }

    private fun compareBars(
        name: String,
        field: String,
        expected: List<JsonObject>,
        actual: List<StatBar>,
        failures: MutableList<String>,
    ) {
        if (expected.size != actual.size) {
            failures += "$name: $field expected ${expected.size} bars, got ${actual.size}"
            return
        }
        for ((i, want) in expected.withIndex()) {
            val got = actual[i]
            if (got.key != want.string("key")) {
                failures += "$name: $field[$i] key expected ${want.string("key")}, got ${got.key}"
            }
            if (got.count != want.double("count").toLong()) {
                failures += "$name: $field[$i] count expected ${want.double("count")}, got ${got.count}"
            }
            if (!Parity.sameNumber(got.fraction, want.double("fraction"))) {
                failures += "$name: $field[$i] fraction expected ${want.double("fraction")}, got ${got.fraction}"
            }
        }
    }

    @Test
    fun `the corpus still contains the cases worth having`() {
        val names = Parity.load("statistics-view.jsonl").map { it.string("name") }

        assertTrue(names.any { it.contains("empty") }, "no empty wardrobe")
        assertTrue(names.any { it.contains("different sizes") }, "nothing testing per-chart scaling")
        assertTrue(names.any { it.contains("two categories") }, "nothing testing key prefixing")
        assertTrue(names.any { it.contains("zero") }, "no zero counts")
        assertTrue(names.any { it.contains("both cases") }, "nothing testing case-insensitive swatches")
        assertTrue(names.any { it.contains("by name") }, "nothing testing the alphabetical sort")
        assertEquals(12, names.size, "the corpus changed size")
    }
}
