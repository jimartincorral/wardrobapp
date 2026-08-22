package com.wardrobapp.domain

import com.wardrobapp.domain.Parity.double
import com.wardrobapp.domain.Parity.optionalDouble
import com.wardrobapp.domain.Parity.sameNumber
import com.wardrobapp.domain.Parity.string
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The colour maths, against every answer the TypeScript gives for the app's own
 * palette plus the malformed shapes that reach it from restored backups and
 * imported URLs -- 1156 pairs, including the 3-digit hex that used to parse to a
 * NaN and the sentinel in the wrong case.
 */
class ColorParityTest {

    @Test
    fun `matches the TypeScript implementation on every colour pair`() {
        val cases = Parity.load("colors.jsonl")
        val failures = mutableListOf<String>()

        for (case in cases) {
            val a = case.string("a")
            val b = case.string("b")

            val expectedDistance = case.optionalDouble("distance")
            val actualDistance = colorDistance(a, b)
            if (!sameNumber(expectedDistance, actualDistance)) {
                failures += "colorDistance($a, $b): expected $expectedDistance, got $actualDistance"
            }

            val expectedSimilarity = case.double("similarity")
            val actualSimilarity = colorSimilarity(a, b)
            if (!sameNumber(expectedSimilarity, actualSimilarity)) {
                failures += "colorSimilarity($a, $b): expected $expectedSimilarity, got $actualSimilarity"
            }

            val expectedRelationship = case.string("relationship")
            val actualRelationship = colorRelationship(a, b).tsKey()
            if (expectedRelationship != actualRelationship) {
                failures += "colorRelationship($a, $b): expected $expectedRelationship, got $actualRelationship"
            }

            val expectedHarmony = case.double("harmony")
            val actualHarmony = colorHarmonyScore(a, b)
            if (!sameNumber(expectedHarmony, actualHarmony)) {
                failures += "colorHarmonyScore($a, $b): expected $expectedHarmony, got $actualHarmony"
            }
        }

        assertTrue(
            failures.isEmpty(),
            "${failures.size} of ${cases.size * 4} colour comparisons diverged from TypeScript:\n" +
                failures.take(20).joinToString("\n")
        )
    }

    @Test
    fun `the fixture actually covers the hard cases`() {
        // A parity suite is only worth as much as its corpus. If the fixture
        // ever regenerates without the malformed and sentinel inputs, these
        // assertions fail rather than letting 1156 easy cases look like coverage.
        val cases = Parity.load("colors.jsonl")

        assertTrue(cases.size > 1000, "expected a substantial corpus, got ${cases.size}")
        assertTrue(
            cases.any { it["distance"].toString() == "null" },
            "no unparseable colour in the corpus: the null path is untested"
        )
        assertTrue(
            cases.any { it.string("relationship") == "unknown" },
            "no multi-colour sentinel in the corpus"
        )
        assertTrue(
            cases.any { it.string("a").trim().length == 4 && it.string("a").startsWith("#") },
            "no 3-digit hex in the corpus: the NaN regression is untested"
        )
    }
}
