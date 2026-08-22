package com.wardrobapp.domain

import com.wardrobapp.domain.Parity.string
import com.wardrobapp.domain.Parity.strings
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Occasion derivation, across every (category, subcategory) pairing the app can
 * produce -- including pairings that do not belong together, which is the only
 * way the category and default fallbacks get exercised.
 */
class OccasionParityTest {

    @Test
    fun `matches the TypeScript implementation for every category and subcategory`() {
        val cases = Parity.load("occasions.jsonl")
        val failures = mutableListOf<String>()

        for (case in cases) {
            val category = case.string("category")
            val subcategories = case.strings("subcategories")
            val expected = case.strings("occasions")
            val actual = occasionsFor(category, subcategories).map { it.id }

            if (expected != actual) {
                failures += "occasionsFor($category, $subcategories): expected $expected, got $actual"
            }
        }

        assertTrue(
            failures.isEmpty(),
            "${failures.size} of ${cases.size} pairings diverged:\n" + failures.take(20).joinToString("\n")
        )
    }

    @Test
    fun `the fixture exercises the fallbacks, not just the happy path`() {
        val cases = Parity.load("occasions.jsonl")

        assertTrue(cases.size >= 500, "expected every pairing, got ${cases.size}")
        assertTrue(
            cases.any { it.strings("subcategories").isEmpty() },
            "no bare-category case: the category fallback is untested"
        )
        assertTrue(
            cases.any { it.strings("occasions").isEmpty() },
            "nothing maps to no occasion: underwear's deliberate empty is untested"
        )
    }
}
