package com.wardrobapp.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * How alike two tag sets are, and when the answer is "no idea".
 *
 * The distinction between 0 and null is the whole reason this is its own
 * function: 0 asserts the tags disagree, null says there is nothing to compare.
 * A caller that blends signals has to tell those apart, or two untagged garments
 * look maximally dissimilar on no evidence at all -- which is what made the
 * duplicate score unreachable before it was fixed.
 */
class TagSimilarityTest {

    @Test
    fun `identical sets are identical`() {
        assertEquals(1.0, jaccardSimilarity(listOf("summer", "casual"), listOf("summer", "casual")))
        // Order is not part of a set.
        assertEquals(1.0, jaccardSimilarity(listOf("casual", "summer"), listOf("summer", "casual")))
        // Nor is case, nor the spaces around a tag somebody typed.
        assertEquals(1.0, jaccardSimilarity(listOf("Summer", " casual "), listOf("summer", "CASUAL")))
    }

    @Test
    fun `sets that share nothing score zero`() {
        assertEquals(0.0, jaccardSimilarity(listOf("summer"), listOf("winter")))
    }

    @Test
    fun `a partial overlap is the share of the union`() {
        // Two of three: {a,b} vs {b,c} share one tag out of three distinct ones.
        assertEquals(1.0 / 3.0, jaccardSimilarity(listOf("a", "b"), listOf("b", "c")))
        // A subset: {a} vs {a,b} share one of two.
        assertEquals(0.5, jaccardSimilarity(listOf("a"), listOf("a", "b")))
        // A repeated tag is still one tag.
        assertEquals(1.0, jaccardSimilarity(listOf("a", "a"), listOf("a")))
    }

    @Test
    fun `two untagged garments abstain rather than disagree`() {
        assertNull(jaccardSimilarity(emptyList(), emptyList()))
        // A blank string is not a tag: `[""] vs [""]` used to score a perfect 1.
        assertNull(jaccardSimilarity(listOf(""), listOf("   ")))
    }

    @Test
    fun `one side having tags is a disagreement, not an abstention`() {
        // There is evidence here -- one garment is tagged summer and the other is
        // tagged nothing at all -- so the answer is 0, not null.
        assertEquals(0.0, jaccardSimilarity(listOf("summer"), emptyList()))
        assertEquals(0.0, jaccardSimilarity(emptyList(), listOf("summer")))
    }
}
