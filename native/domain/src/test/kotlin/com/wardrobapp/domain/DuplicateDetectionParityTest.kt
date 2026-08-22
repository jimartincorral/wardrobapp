package com.wardrobapp.domain

import com.wardrobapp.domain.Parity.double
import com.wardrobapp.domain.Parity.objects
import com.wardrobapp.domain.Parity.sameNumber
import com.wardrobapp.domain.Parity.string
import com.wardrobapp.domain.Parity.stringOrNull
import com.wardrobapp.domain.Parity.strings
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Duplicate scoring, against the TypeScript answers for 60 scenarios -- each one
 * a candidate scored against seven existing garments, so 420 comparisons whose
 * score, order and reported reasons must all agree.
 */
class DuplicateDetectionParityTest {

    private fun garmentFrom(json: JsonObject) = Garment(
        id = json.string("id"),
        category = json.string("category"),
        tags = json.strings("tags"),
        colorPrimary = json.string("color_primary"),
        colorPalette = json.strings("color_palette"),
        size = json.stringOrNull("size"),
    )

    private fun candidateFrom(json: JsonObject) = DuplicateCandidate(
        category = json.string("category"),
        tags = json.strings("tags"),
        colorPrimary = json.string("color_primary"),
        colorPalette = json.strings("color_palette"),
        size = json.stringOrNull("size"),
    )

    @Test
    fun `matches the TypeScript implementation on every scenario`() {
        val cases = Parity.load("duplicates.jsonl")
        val failures = mutableListOf<String>()
        var comparisons = 0

        for ((index, case) in cases.withIndex()) {
            val candidate = candidateFrom(case["candidate"]!! as JsonObject)
            val existing = case.objects("existing").map(::garmentFrom)
            val expected = case.objects("matches")

            // Threshold -1 to mirror the dump: every pair is reported, so the
            // fixture pins every score rather than only the ones that clear the
            // default bar.
            val actual = findDuplicatesAmong(candidate, existing, -1.0)
            comparisons += expected.size

            if (actual.size != expected.size) {
                failures += "scenario $index: expected ${expected.size} matches, got ${actual.size}"
                continue
            }

            // Order is part of the contract: the UI shows the top match first.
            for ((i, expectedMatch) in expected.withIndex()) {
                val actualMatch = actual[i]
                val expectedId = expectedMatch.string("id")

                if (actualMatch.garment.id != expectedId) {
                    failures += "scenario $index rank $i: expected $expectedId, got ${actualMatch.garment.id}"
                    continue
                }

                val expectedScore = expectedMatch.double("score")
                if (!sameNumber(expectedScore, actualMatch.score)) {
                    failures += "scenario $index, $expectedId: expected score $expectedScore, got ${actualMatch.score}"
                }

                val expectedReason = expectedMatch.string("reason")
                val actualReason = actualMatch.reasons.joinToString(", ") { it.tsKey() }
                if (expectedReason != actualReason) {
                    failures += "scenario $index, $expectedId: expected reasons '$expectedReason', got '$actualReason'"
                }
            }
        }

        assertTrue(
            failures.isEmpty(),
            "${failures.size} divergences across $comparisons comparisons:\n" +
                failures.take(20).joinToString("\n")
        )
    }

    @Test
    fun `the fixture spans the cases the score has to get right`() {
        val cases = Parity.load("duplicates.jsonl")
        val allMatches = cases.flatMap { it.objects("matches") }

        assertTrue(cases.size >= 40, "expected a broad scenario set, got ${cases.size}")
        assertTrue(
            allMatches.any { sameNumber(it.double("score"), 1.0) },
            "no exact duplicate in the corpus"
        )
        assertTrue(
            allMatches.any { it.string("reason") == "duplicateReasons.overallSimilarity" },
            "the generic-reason fallback is never exercised"
        )
        assertTrue(
            cases.any { case ->
                case.objects("existing").any { it["size"].toString() == "null" }
            },
            "no absent size in the corpus: the abstention path is untested"
        )
    }
}
