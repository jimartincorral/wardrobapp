package com.wardrobapp.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The feature row an outfit becomes, and the model fitted to rows of them.
 *
 * The outfits are built from garments rather than feature rows typed by
 * hand, so what is tested is the whole way from a wardrobe to a weight.
 */
class TasteModelTest {

    private fun garment(id: String, category: String, type: String, color: String = "#000000", vararg tags: String) =
        Garment(id = id, category = category, subcategory = type, colorPrimary = color, tags = tags.toList())

    private fun feature(features: DoubleArray, feature: OutfitFeature) = features[feature.ordinal]

    @Test
    fun `an outfit of one formality has no spread, and a mixed one does`() {
        val suit = listOf(garment("b", "midlayer", "Blazer"), garment("p", "bottoms", "Pants"), garment("l", "shoes", "Loafers"))
        val mixed = listOf(garment("b", "midlayer", "Blazer"), garment("s", "bottoms", "Sweatpants"), garment("l", "shoes", "Loafers"))

        assertEquals(0.0, feature(outfitFeatures(suit), OutfitFeature.FORMALITY_SPREAD))
        // Smart (4) down to lounge (1): three of the four steps there are.
        assertEquals(0.75, feature(outfitFeatures(mixed), OutfitFeature.FORMALITY_SPREAD))
        assertTrue(feature(outfitFeatures(suit), OutfitFeature.FORMALITY_MEAN) > 0, "a suit is not dressed up")
        assertEquals(1.0, feature(outfitFeatures(suit), OutfitFeature.BIAS))
    }

    @Test
    fun `two patterned pieces clash and one does not`() {
        val one = listOf(garment("t", "tops", "Shirt", tags = arrayOf("pattern:stripes")), garment("j", "bottoms", "Jeans"))
        val two = listOf(garment("t", "tops", "Shirt", tags = arrayOf("pattern:stripes")), garment("j", "bottoms", "Jeans", tags = arrayOf("pattern:checks")))

        assertEquals(0.0, feature(outfitFeatures(one), OutfitFeature.PATTERN_CLASH))
        assertEquals(1.0, feature(outfitFeatures(two), OutfitFeature.PATTERN_CLASH))
    }

    @Test
    fun `the harmony shares add up to the pairs there are`() {
        val outfit = listOf(
            garment("t", "tops", "Shirt", "#1F3A93"),
            garment("j", "bottoms", "Jeans", "#C0392B"),
            garment("s", "shoes", "Sneakers", "#FFFFFF"),
        )
        val features = outfitFeatures(outfit)
        val shares = listOf(
            OutfitFeature.HARMONY_SAME, OutfitFeature.HARMONY_NEUTRAL, OutfitFeature.HARMONY_ANALOGOUS,
            OutfitFeature.HARMONY_NEAR_MISS, OutfitFeature.HARMONY_CONTRASTING,
        ).sumOf { feature(features, it) }

        assertEquals(1.0, shares, absoluteTolerance = 1e-9)
    }

    @Test
    fun `nothing is fitted to too few ratings`() {
        val example = RatedExample(outfitFeatures(listOf(garment("t", "tops", "Shirt"), garment("j", "bottoms", "Jeans"))), 5)
        assertNull(fitTasteModel(List(TasteModel.MIN_EXAMPLES - 1) { example }))
    }

    /**
     * Twenty outfits, half of them with a patterned top over patterned
     * trousers and half plain, rated by somebody who hates the clash and is
     * otherwise indifferent: the fitted weight on the clash is the one that
     * is clearly negative.
     */
    private fun clashHater(): List<RatedExample> = (0 until 20).map { i ->
        val clashing = i % 2 == 0
        val top = garment("t$i", "tops", "Shirt", tags = arrayOf("pattern:stripes"))
        val bottom = garment("b$i", "bottoms", "Jeans", tags = if (clashing) arrayOf("pattern:checks") else emptyArray())
        // A little variety elsewhere, so the clash is not the only thing that varies.
        val shoes = garment("s$i", "shoes", if (i % 3 == 0) "Loafers" else "Sneakers")
        RatedExample(outfitFeatures(listOf(top, bottom, shoes)), if (clashing) 1 else 5)
    }

    @Test
    fun `a planted dislike is recovered from the ratings`() {
        val model = fitTasteModel(clashHater())!!

        val clash = model.weights[OutfitFeature.PATTERN_CLASH.ordinal]
        assertTrue(clash < -0.3, "the clash weight came out $clash")
        // And it is what tells the two kinds of outfit apart.
        val plain = outfitFeatures(listOf(garment("t", "tops", "Shirt", tags = arrayOf("pattern:stripes")), garment("b", "bottoms", "Jeans")))
        val clashing = outfitFeatures(listOf(garment("t", "tops", "Shirt", tags = arrayOf("pattern:stripes")), garment("b", "bottoms", "Jeans", tags = arrayOf("pattern:checks"))))
        assertTrue(model.score(plain) > model.score(clashing) + 0.5, "the model does not prefer the plain outfit")
    }

    @Test
    fun `the same ratings in any order give the same model`() {
        val examples = clashHater()
        val forward = fitTasteModel(examples)!!.weights
        val backward = fitTasteModel(examples.reversed())!!.weights

        for (i in forward.indices) {
            assertTrue(abs(forward[i] - backward[i]) < 1e-9, "weight $i differs with the order")
        }
    }

    @Test
    fun `an opinion grows with the evidence for it`() {
        // Five of the clash-hating ratings against all twenty: the ridge pull
        // lets go as the examples pile up, so the same dislike weighs more
        // once it has been said more often. The sign is there from the start.
        val few = fitTasteModel(clashHater().take(TasteModel.MIN_EXAMPLES))!!
        val many = fitTasteModel(clashHater())!!
        val fewClash = few.weights[OutfitFeature.PATTERN_CLASH.ordinal]
        val manyClash = many.weights[OutfitFeature.PATTERN_CLASH.ordinal]

        assertTrue(fewClash < 0, "five ratings taught nothing")
        assertTrue(manyClash < fewClash, "twenty ratings ($manyClash) did not outweigh five ($fewClash)")
    }
}
