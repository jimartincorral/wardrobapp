package com.wardrobapp.domain

import com.wardrobapp.domain.Parity.optionalDouble
import com.wardrobapp.domain.Parity.sameNumber
import com.wardrobapp.domain.Parity.strings
import kotlin.test.Test
import kotlin.test.assertTrue

class TagSimilarityParityTest {

    @Test
    fun `matches the TypeScript implementation on every tag pair`() {
        val cases = Parity.load("tags.jsonl")
        val failures = mutableListOf<String>()

        for (case in cases) {
            val a = case.strings("a")
            val b = case.strings("b")
            val expected = case.optionalDouble("similarity")
            val actual = jaccardSimilarity(a, b)

            if (!sameNumber(expected, actual)) {
                failures += "jaccardSimilarity($a, $b): expected $expected, got $actual"
            }
        }

        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `the fixture covers abstention and disagreement separately`() {
        val cases = Parity.load("tags.jsonl")

        assertTrue(
            cases.any { it["similarity"].toString() == "null" },
            "no abstaining pair in the corpus"
        )
        assertTrue(
            cases.any { sameNumber(it.optionalDouble("similarity"), 0.0) },
            "no genuinely-disagreeing pair in the corpus"
        )
    }
}
