package com.wardrobapp.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Colour comparison, which is what decides whether two garments go together.
 *
 * The arithmetic is sRGB to CIE Lab and a Euclidean distance in it (CIE76), and
 * it used to be checked against
 * 1156 colour pairs recorded from the TypeScript this was ported from. Breadth
 * was the whole value of that corpus, and it is gone; what these tests keep is
 * the set of properties the function has to have for the suggestion engine and
 * the duplicate check to mean anything -- plus the handful of pairs whose answer
 * the palette was actually tuned against.
 */
class ColorsTest {

    private fun assertClose(expected: Double, actual: Double?, what: String) {
        assertTrue(actual != null && abs(expected - actual) < 0.5, "$what: expected ~$expected, got $actual")
    }

    @Test
    fun `a hex colour is read in every form it is written`() {
        assertEquals(Rgb(255, 0, 0), parseHexColor("#FF0000"))
        assertEquals(Rgb(255, 0, 0), parseHexColor("ff0000"))
        assertEquals(Rgb(255, 0, 0), parseHexColor("  #Ff0000  "))
        // Three digits, the way CSS shorthands them.
        assertEquals(Rgb(255, 0, 0), parseHexColor("#f00"))
    }

    @Test
    fun `something that is not a colour reads as nothing, not as black`() {
        // The difference matters: black is a colour a garment can be, and a
        // failed parse must not become one.
        assertNull(parseHexColor(""))
        assertNull(parseHexColor("#12345"))
        assertNull(parseHexColor("#GGGGGG"))
        assertNull(parseHexColor("rebeccapurple"))
        assertNull(colorDistance("#FF0000", "not a colour"))
        assertNull(colorDistance("also not", "#FF0000"))
    }

    @Test
    fun `a colour is identical to itself, whatever case it was typed in`() {
        assertEquals(0.0, colorDistance("#1F3A93", "#1f3a93"))
        assertEquals(0.0, colorDistance("#000000", "#000000"))
        assertEquals(1.0, colorSimilarity("#C0392B", "#c0392b"))
    }

    @Test
    fun `distance is symmetric`() {
        // Asymmetry here would make a duplicate check depend on which garment was
        // added first.
        val pairs = listOf(
            "#FF0000" to "#00FF00",
            "#1F3A93" to "#C0392B",
            "#FFFFFF" to "#000000",
            "#BDC3C7" to "#7F8C8D",
        )

        for ((a, b) in pairs) {
            assertEquals(colorDistance(a, b), colorDistance(b, a), "$a vs $b")
        }
    }

    @Test
    fun `a small difference is a small distance and a large one is not`() {
        val nearlyBlack = colorDistance("#000000", "#050505")!!
        val blackToWhite = colorDistance("#000000", "#FFFFFF")!!
        val navyToBlue = colorDistance("#1F3A93", "#2E5FD4")!!

        assertTrue(nearlyBlack < SAME_COLOR_DELTA_E, "near-black pair read as different: $nearlyBlack")
        assertTrue(blackToWhite > 90, "black and white read as close: $blackToWhite")
        assertTrue(navyToBlue in nearlyBlack..blackToWhite, "navy to blue is out of order: $navyToBlue")
        // Same distance, expressed the other way: similarity falls as distance
        // rises, and never goes negative.
        assertTrue(colorSimilarity("#000000", "#050505") > colorSimilarity("#000000", "#FFFFFF"))
        assertTrue(colorSimilarity("#000000", "#FFFFFF") >= 0.0)
    }

    @Test
    fun `an unknown colour is no information rather than a match`() {
        assertEquals(0.0, colorSimilarity("#FF0000", "nonsense"))
        assertEquals(ColorRelationship.UNKNOWN, colorRelationship("#FF0000", "nonsense"))
        assertEquals(0.0, colorHarmonyScore("#FF0000", "nonsense"))
    }

    @Test
    fun `multi-coloured is its own answer`() {
        // A patterned garment has no single colour, so it is neither close to nor
        // far from anything -- except another multi-coloured garment, which is
        // the same answer as itself.
        assertEquals(MULTI_COLOR_DISTANCE, colorDistance(MULTI_COLOR, "#FF0000"))
        assertEquals(MULTI_COLOR_DISTANCE, colorDistance("#FF0000", MULTI_COLOR))
        assertEquals(0.0, colorDistance(MULTI_COLOR, MULTI_COLOR))
        assertEquals(ColorRelationship.UNKNOWN, colorRelationship(MULTI_COLOR, "#FF0000"))
    }

    @Test
    fun `the pairs the palette was tuned against land where they were meant to`() {
        // These are the cases the bands in colorRelationship were set from, so
        // they are the ones worth pinning: a change to the thresholds that breaks
        // any of them has changed what the app calls a match.
        assertEquals(ColorRelationship.SAME, colorRelationship("#1F3A93", "#1F3A95"))
        assertEquals(ColorRelationship.ANALOGOUS, colorRelationship("#1F3A93", "#2E5FD4"))
        assertEquals(ColorRelationship.ANALOGOUS, colorRelationship("#C0392B", "#8B0000"))
        assertEquals(ColorRelationship.NEAR_MISS, colorRelationship("#C0392B", "#D4AC0D"))
        assertEquals(ColorRelationship.CONTRASTING, colorRelationship("#1F3A93", "#C0392B"))
        // Greys, beiges and whites have no hue worth comparing, and go with
        // anything -- which is why this is derived from chroma rather than from a
        // list of four hexes.
        assertEquals(ColorRelationship.NEUTRAL, colorRelationship("#FFFFFF", "#C0392B"))
        assertEquals(ColorRelationship.NEUTRAL, colorRelationship("#BDC3C7", "#1F3A93"))
        assertEquals(ColorRelationship.NEUTRAL, colorRelationship("#F5F0E1", "#C0392B"))
    }

    @Test
    fun `harmony never punishes a real contrast`() {
        // Navy and red is a deliberate outfit, and an earlier version of this
        // scored it below an accidental near-miss -- which pushed the suggestion
        // engine away from the pairings people actually wear. Terracotta and gold
        // is the near-miss: close enough in hue to look unintended.
        val contrast = colorHarmonyScore("#1F3A93", "#C0392B")
        val nearMiss = colorHarmonyScore("#C0392B", "#D4AC0D")

        assertTrue(contrast > nearMiss, "a contrast scored below a near-miss")
        assertTrue(contrast >= 0.0 && nearMiss >= 0.0, "harmony went negative")

        // Every relationship has a score, and no relationship scores above 1.
        for (score in listOf(
            colorHarmonyScore("#1F3A93", "#1F3A95"),
            colorHarmonyScore("#FFFFFF", "#C0392B"),
            colorHarmonyScore("#1F3A93", "#2E5FD4"),
            contrast,
            nearMiss,
            colorHarmonyScore(MULTI_COLOR, "#1F3A93"),
        )) {
            assertTrue(score in 0.0..1.0, "harmony out of range: $score")
        }
    }

    @Test
    fun `the distance is CIE76, and still lands where CIE76 lands`() {
        // Anchors computed from the published Lab values rather than from this
        // implementation, so an arithmetic slip in the sRGB-to-Lab conversion
        // cannot pass by moving every comparison equally.
        //
        // White is L*100 and black is L*0 with no chroma either side, so the
        // distance between them is exactly the lightness range. Red (53.24,
        // 80.09, 67.20) to green (87.73, -86.18, 83.18) is the textbook
        // wide-hue pair: sqrt(34.49^2 + 166.27^2 + 15.98^2).
        assertClose(100.0, colorDistance("#FFFFFF", "#000000"), "white to black")
        assertClose(170.57, colorDistance("#FF0000", "#00FF00"), "red to green")

        // Worth stating because the scale surprises people: CIE76 over sRGB runs
        // past 100, and the near-miss band sits around 90 rather than at some
        // fraction of a hundred.
        assertTrue(colorDistance("#FF0000", "#0000FF")!! > 100.0)
    }
}
