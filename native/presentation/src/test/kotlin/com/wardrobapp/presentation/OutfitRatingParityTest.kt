package com.wardrobapp.presentation

import com.wardrobapp.parity.Parity
import com.wardrobapp.parity.Parity.double
import com.wardrobapp.parity.Parity.optionalDouble
import com.wardrobapp.parity.Parity.string
import com.wardrobapp.parity.Parity.stringOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The rating summary, replayed against the answers the TypeScript gave.
 *
 * The formatted label is compared as well as the numbers, because it is what the
 * caption actually shows and because it is the part most likely to drift: a
 * locale-aware format would put a comma in it on half the devices this runs on.
 */
class OutfitRatingParityTest {

    @Test
    fun `it agrees with the TypeScript`() {
        val cases = Parity.load("outfit-rating.jsonl")
        val failures = mutableListOf<String>()

        for (case in cases) {
            val name = case.string("name")
            val ratings = case.getValue("input").jsonObject
                .getValue("ratings").jsonArray
                .map { (it as JsonPrimitive).content.toDouble().toInt() }

            val expected = case.getValue("summary").jsonObject
            val actual = ratingSummary(ratings)

            if (actual.count != expected.double("count").toInt()) {
                failures += "$name: count expected ${expected.double("count")}, got ${actual.count}"
            }

            val expectedAverage = expected.optionalDouble("average")
            if (expectedAverage == null) {
                if (actual.average != null) failures += "$name: expected no average, got ${actual.average}"
            } else if (actual.average == null || !Parity.sameNumber(actual.average, expectedAverage)) {
                failures += "$name: average expected $expectedAverage, got ${actual.average}"
            }

            if (actual.stars != expected.double("stars").toInt()) {
                failures += "$name: stars expected ${expected.double("stars")}, got ${actual.stars}"
            }

            if (actual.label != expected.stringOrNull("label")) {
                failures += "$name: label expected ${expected.stringOrNull("label")}, got ${actual.label}"
            }
        }

        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `the corpus still contains the cases worth having`() {
        // So a regenerated fixture cannot quietly drop the hard ones and leave
        // this suite passing over nothing but easy averages.
        val names = Parity.load("outfit-rating.jsonl").map { it.string("name") }

        assertTrue(names.any { it.contains("no ratings") }, "nothing unrated in the corpus")
        assertTrue(names.any { it.contains("half") }, "nothing on a rounding boundary")
        assertTrue(names.any { it.contains("zero") }, "nothing with a zero rating")
        assertTrue(names.any { it.contains("above the scale") }, "nothing beyond the scale")
        assertEquals(17, names.size, "the corpus changed size")
    }
}
