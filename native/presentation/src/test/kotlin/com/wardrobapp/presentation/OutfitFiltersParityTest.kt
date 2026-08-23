package com.wardrobapp.presentation

import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import com.wardrobapp.parity.Parity
import com.wardrobapp.parity.Parity.objects
import com.wardrobapp.parity.Parity.string
import com.wardrobapp.parity.Parity.stringOrNull
import com.wardrobapp.parity.Parity.strings
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The filter chips, tap by tap, against the TypeScript.
 *
 * Compared after every tap rather than at the end of each sequence: two
 * implementations can disagree in the middle and coincide by the finish.
 */
class OutfitFiltersParityTest {

    private fun chips(expected: JsonObject, key: String): List<Pair<String?, Boolean>> =
        expected.objects(key).map { it.stringOrNull("value") to (it.string("active") == "true") }

    @Test
    fun `matches the TypeScript after every tap`() {
        val cases = Parity.load("outfit-filters.jsonl")
        val failures = mutableListOf<String>()
        var filters = OutfitFilters()

        for (case in cases) {
            val step = case.string("step").toInt()
            val script = case.string("script")

            // Each script restarts from nothing, and its step 0 records that
            // starting row -- so an implementation that stored "any" instead of
            // deriving it would already differ before any tap.
            if (step == 0) {
                filters = OutfitFilters()
            } else {
                val tap = case["tap"]!!.jsonObject
                val value = tap.stringOrNull("value")
                filters = when (val row = tap.string("row")) {
                    "season" -> filters.withSeasonToggled(value?.let(::seasonNamed))
                    "occasion" -> filters.withOccasionSelected(value?.let(::occasionNamed))
                    else -> fail("Unknown chip row in fixture: $row")
                }
            }

            val expected = case["state"]!!.jsonObject
            val where = "\"$script\" after step $step"

            fun check(field: String, expectedValue: Any?, actualValue: Any?) {
                if (expectedValue != actualValue) {
                    failures += "$where .$field: expected $expectedValue, got $actualValue"
                }
            }

            check("seasons", expected.strings("seasons"), filters.seasons.map { it.tag })
            check("occasion", expected.stringOrNull("occasion"), filters.occasion?.id)
            check("unfiltered", expected.string("unfiltered") == "true", filters.isUnfiltered)
            check(
                "seasonChips",
                chips(expected, "seasonChips"),
                filters.seasonChips().map { it.value?.tag to it.active },
            )
            check(
                "occasionChips",
                chips(expected, "occasionChips"),
                filters.occasionChips().map { it.value?.id to it.active },
            )
        }

        assertTrue(
            failures.isEmpty(),
            "${failures.size} divergences across ${cases.size} steps:\n" +
                failures.take(15).joinToString("\n"),
        )
    }

    @Test
    fun `the corpus taps every chip there is`() {
        // A chip the corpus never touches is a chip the port is not compared on.
        val tapped = Parity.load("outfit-filters.jsonl")
            .mapNotNull { it["tap"]?.takeIf { tap -> tap !is JsonNull }?.jsonObject }
            .map { it.string("row") to it.stringOrNull("value") }
            .toSet()

        for (season in Season.entries) {
            assertTrue("season" to season.tag in tapped, "no case taps the ${season.tag} chip")
        }
        for (occasion in Occasion.entries) {
            assertTrue("occasion" to occasion.id in tapped, "no case taps the ${occasion.id} chip")
        }
        assertTrue("season" to null in tapped, "no case taps the season \"any\" chip")
        assertTrue("occasion" to null in tapped, "no case taps the occasion \"any\" chip")
    }

    private fun seasonNamed(tag: String): Season =
        Season.fromTag(tag) ?: fail("Unknown season in fixture: $tag")

    private fun occasionNamed(id: String): Occasion =
        Occasion.fromId(id) ?: fail("Unknown occasion in fixture: $id")
}
