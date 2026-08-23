package com.wardrobapp.presentation

import com.wardrobapp.parity.Parity
import com.wardrobapp.parity.Parity.double
import com.wardrobapp.parity.Parity.objects
import com.wardrobapp.parity.Parity.string
import com.wardrobapp.parity.Parity.strings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The analytics bars, against the TypeScript.
 *
 * A corpus of shapes rather than realistic wardrobes: the arithmetic is what is
 * being compared, so the interesting inputs are at the edges -- an empty
 * wardrobe, counts that exceed the total, a negative lifespan, a garment owned
 * for a decade.
 */
class AnalyticsViewParityTest {

    private fun entriesFrom(json: JsonObject): List<LifespanEntry> =
        json.objects("lifespans").map {
            LifespanEntry(
                garmentId = it.string("garmentId"),
                category = it.string("category"),
                subcategories = it.strings("subcategories"),
                days = it.string("days").toLong(),
            )
        }

    @Test
    fun `matches the TypeScript for every shape`() {
        val cases = Parity.load("analytics-view.jsonl")
        val failures = mutableListOf<String>()

        for (case in cases) {
            val name = case.string("name")
            val input = case["input"]!!.jsonObject
            val expected = case["view"]!!.jsonObject

            val actual = analyticsView(
                totalItems = input.string("totalItems").toLong(),
                archivedItems = input.string("archivedItems").toLong(),
                categoryCounts = input.objects("categoryCounts")
                    .map { it.string("category") to it.string("count").toLong() },
                lifespans = entriesFrom(input),
            )

            fun check(field: String, expectedValue: Any?, actualValue: Any?) {
                if (expectedValue != actualValue) {
                    failures += "\"$name\" .$field: expected $expectedValue, got $actualValue"
                }
            }

            check("totalItems", expected.string("totalItems").toLong(), actual.totalItems)
            check("archivedItems", expected.string("archivedItems").toLong(), actual.archivedItems)
            check("isEmpty", expected.string("isEmpty") == "true", actual.isEmpty)

            val expectedCategories = expected.objects("categories")
            check("categories.size", expectedCategories.size, actual.categories.size)
            for ((bar, expectedBar) in actual.categories.zip(expectedCategories)) {
                check("categories[${bar.key}].category", expectedBar.string("category"), bar.category)
                check("categories[${bar.key}].value", expectedBar.string("value").toLong(), bar.value)
                if (!Parity.sameNumber(expectedBar.double("fraction"), bar.fraction)) {
                    failures += "\"$name\" categories[${bar.key}].fraction: " +
                        "expected ${expectedBar.double("fraction")}, got ${bar.fraction}"
                }
            }

            val expectedLifespans = expected.objects("lifespans")
            check("lifespans.size", expectedLifespans.size, actual.lifespans.size)
            for ((bar, expectedBar) in actual.lifespans.zip(expectedLifespans)) {
                check("lifespans[${bar.key}].key", expectedBar.string("key"), bar.key)
                check("lifespans[${bar.key}].value", expectedBar.string("value").toLong(), bar.value)
                check("lifespans[${bar.key}].category", expectedBar.string("category"), bar.entry.category)
                check(
                    "lifespans[${bar.key}].subcategories",
                    expectedBar.strings("subcategories"),
                    bar.entry.subcategories,
                )
                if (!Parity.sameNumber(expectedBar.double("fraction"), bar.fraction)) {
                    failures += "\"$name\" lifespans[${bar.key}].fraction: " +
                        "expected ${expectedBar.double("fraction")}, got ${bar.fraction}"
                }
            }
        }

        assertTrue(
            failures.isEmpty(),
            "${failures.size} divergences across ${cases.size} cases:\n" +
                failures.take(15).joinToString("\n"),
        )
    }

    @Test
    fun `no bar can be drawn outside its track`() {
        // Stated over the whole corpus rather than case by case, because this is
        // the property the clamping exists for and a new fixture case should not
        // be able to slip past it.
        for (case in Parity.load("analytics-view.jsonl")) {
            val view = case["view"]!!.jsonObject

            for (bar in view.objects("categories") + view.objects("lifespans")) {
                val fraction = bar.double("fraction")
                assertTrue(
                    fraction in 0.0..1.0 && !fraction.isNaN(),
                    "\"${case.string("name")}\" has a bar at $fraction",
                )
            }
        }
    }

    @Test
    fun `the corpus covers the edges the arithmetic can fail at`() {
        val cases = Parity.load("analytics-view.jsonl")
        val views = cases.map { it["view"]!!.jsonObject }
        val inputs = cases.map { it["input"]!!.jsonObject }

        assertTrue(views.any { it.string("isEmpty") == "true" }, "no empty wardrobe")
        assertTrue(
            inputs.any { input ->
                val total = input.string("totalItems").toLong()
                input.objects("categoryCounts").any { it.string("count").toLong() > total }
            },
            "no case where the counts exceed the total",
        )
        assertTrue(
            inputs.any { input -> entriesFrom(input).any { it.days < 0 } },
            "no case with a garment retired before it was bought",
        )
        assertTrue(
            inputs.any { input -> entriesFrom(input).any { it.days > LIFESPAN_FULL_BAR_DAYS } },
            "no case with a garment owned longer than the bar can show",
        )
        assertTrue(
            inputs.any { entriesFrom(it).size > LIFESPAN_BARS },
            "no case with more lifespans than the chart has room for",
        )
    }

    @Test
    fun `keeps only as many lifespans as the chart has room for`() {
        val entries = (1..10).map {
            LifespanEntry("g$it", "tops", listOf("T-Shirt"), (100 - it).toLong())
        }

        val view = analyticsView(5, 10, emptyList(), entries)

        assertEquals(LIFESPAN_BARS, view.lifespans.size)
        assertEquals(listOf("g1", "g2", "g3"), view.lifespans.map { it.key })
    }
}
