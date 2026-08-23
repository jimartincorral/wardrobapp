package com.wardrobapp.domain

import com.wardrobapp.parity.Parity
import com.wardrobapp.parity.Parity.double
import com.wardrobapp.parity.Parity.optionalDouble
import com.wardrobapp.parity.Parity.sameNumber
import com.wardrobapp.parity.Parity.strings
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The pair-learning arithmetic, against the TypeScript's answer for every
 * rating transition -- including corrections, whose undo step has to move a
 * score to where it would have been had the user rated correctly the first
 * time, without counting a second wear.
 */
class PairLearningParityTest {

    private fun existingFrom(element: kotlinx.serialization.json.JsonElement?): PairScore? {
        if (element == null || element is JsonNull) return null
        val obj = element.jsonObject
        return PairScore(
            score = obj.double("score"),
            wearCount = obj.double("wear_count").toInt(),
        )
    }

    @Test
    fun `matches the TypeScript on every rating transition`() {
        val cases = Parity.load("pair-learning.jsonl")
        val failures = mutableListOf<String>()

        for ((index, case) in cases.withIndex()) {
            val existing = existingFrom(case["existing"])
            val rating = case.double("rating").toInt()
            val previous = case.optionalDouble("previous")?.toInt()
            val expected = case["next"]!!.jsonObject

            val actual = foldRatingIntoPair(existing, rating, previous)
            val where = "case $index (existing=$existing rating=$rating previous=$previous)"

            if (!sameNumber(expected.double("score"), actual.score)) {
                failures += "$where score: expected ${expected.double("score")}, got ${actual.score}"
            }
            val expectedWear = expected.double("wear_count").toInt()
            if (expectedWear != actual.wearCount) {
                failures += "$where wearCount: expected $expectedWear, got ${actual.wearCount}"
            }
        }

        assertTrue(
            failures.isEmpty(),
            "${failures.size} divergences across ${cases.size} transitions:\n" +
                failures.take(20).joinToString("\n")
        )
    }

    @Test
    fun `matches the TypeScript on pair enumeration`() {
        val cases = Parity.load("garment-pairs.jsonl")
        val failures = mutableListOf<String>()

        for (case in cases) {
            val ids = case.strings("ids")
            val expected = case["pairs"]!!.jsonArray.map { pair ->
                pair.jsonArray.map { it.jsonPrimitive.content }
            }
            val actual = garmentPairs(ids).map { listOf(it.first, it.second) }

            if (expected != actual) {
                failures += "garmentPairs($ids): expected $expected, got $actual"
            }
        }

        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `the fixture covers corrections, not just fresh ratings`() {
        val cases = Parity.load("pair-learning.jsonl")

        assertTrue(cases.size >= 150, "expected a broad corpus, got ${cases.size}")
        assertTrue(
            cases.any { it["previous"] !is JsonNull && it["previous"] != null },
            "no correction in the corpus: the undo step is untested"
        )
        assertTrue(
            cases.any { it["existing"] is JsonNull },
            "no first-ever rating in the corpus"
        )
        // A correction chain reaches scores the round-numbered starting states do
        // not, which is where an inexact inverse would show up.
        assertTrue(
            cases.count { case ->
                val existing = case["existing"]
                existing !is JsonNull && existing != null &&
                    (existing as JsonObject).double("score").let { it != 0.0 && kotlin.math.abs(it) !in setOf(0.3, 0.9) }
            } >= 4,
            "no off-grid starting scores: an inexact undo could hide"
        )
    }

    @Test
    fun `a correction undoes the rating it replaces`() {
        // Stated directly, not just via the fixture: rating 5 then correcting to
        // 1 must land exactly where rating 1 alone would have.
        val rated5 = foldRatingIntoPair(null, 5)
        val corrected = foldRatingIntoPair(rated5, 1, previous = 5)
        val rated1 = foldRatingIntoPair(null, 1)

        assertTrue(
            sameNumber(rated1.score, corrected.score),
            "correcting 5 to 1 gave ${corrected.score}, rating 1 alone gives ${rated1.score}"
        )
        assertTrue(
            corrected.wearCount == rated5.wearCount,
            "a correction must not count another wear: ${corrected.wearCount} vs ${rated5.wearCount}"
        )
    }
}
