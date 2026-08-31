package com.wardrobapp.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gap analysis.
 *
 * Every test here fixes the draws, because a gap that moved between two runs of
 * the same wardrobe would be a gap nobody could investigate -- and the factory in
 * [GapContext] exists precisely so that the answer does not depend on how many
 * candidates were measured before it.
 *
 * What is asserted is mostly the *shape* of an answer rather than a particular
 * garment: which slot, which evidence, whether the examples are real. Pinning
 * "it should suggest brown loafers" would be pinning the scoring function's
 * current taste, and the scoring function is allowed to change its mind.
 */
class WardrobeGapsTest {

    /**
     * A linear congruential generator, matching OutfitSuggestionsTest's.
     *
     * Wrapped in a factory, which is the whole point: every candidate gets a
     * generator starting from the same seed, so two candidates measured in either
     * order are measured identically.
     */
    private fun seeds(seed: Long = 42): () -> () -> Double = {
        var state = seed and 0xFFFFFFFFL
        {
            state = (state * 1664525 + 1013904223) and 0xFFFFFFFFL
            state.toDouble() / 4294967296.0
        }
    }

    private fun garment(
        id: String,
        category: String,
        subcategory: String? = null,
        tags: List<String> = emptyList(),
        color: String = "#000000",
        available: Boolean = true,
    ) = Garment(
        id = id,
        category = category,
        subcategory = subcategory,
        tags = tags,
        colorPrimary = color,
        isAvailable = available,
    )

    private fun context(
        garments: List<Garment>,
        season: Season = Season.FALL,
        seed: Long = 42,
    ) = GapContext(
        garments = garments,
        getPairScore = PairScoreLookup { _, _ -> 0.0 },
        currentSeason = season,
        newRandom = seeds(seed),
    )

    /**
     * Twelve garments, no shoes at all.
     *
     * Over [MIN_WARDROBE_FOR_GAPS] so the analysis will speak, and varied enough
     * in colour that the anchors are not all one shade of black.
     */
    private val shoelessWardrobe = listOf(
        garment("top-white", "tops", "T-Shirt", color = "#FFFFFF"),
        garment("top-navy", "tops", "Shirt", color = "#1F3A93"),
        garment("top-grey", "tops", "Shirt", color = "#7F8C8D"),
        garment("top-red", "tops", "Blouse", color = "#C0392B"),
        garment("bottom-jeans", "bottoms", "Jeans", color = "#2C3E50"),
        garment("bottom-chinos", "bottoms", "Chinos", color = "#BDC3C7"),
        garment("bottom-black", "bottoms", "Pants", color = "#000000"),
        garment("skirt-navy", "bottoms", "Skirt", color = "#1F3A93"),
        garment("coat-camel", "outerwear", "Coat", color = "#B8860B"),
        garment("cardigan-grey", "outerwear", "Cardigan", color = "#7F8C8D"),
        garment("hat-black", "accessories", "Hat", color = "#000000"),
        garment("bag-brown", "accessories", "Bag", color = "#6B4423"),
    )

    // --- Reproducibility -------------------------------------------------------

    @Test
    fun `the same wardrobe reports the same gaps twice running`() {
        val first = analyzeGaps(context(shoelessWardrobe))
        val second = analyzeGaps(context(shoelessWardrobe))

        assertEquals(first.map { it.want }, second.map { it.want })
        assertEquals(first.map { it.slot }, second.map { it.slot })
        assertEquals(first.map { it.outfitsUnlocked }, second.map { it.outfitsUnlocked })
    }

    /**
     * The bug the factory in [GapContext] exists to prevent: with one shared
     * generator, a candidate's draws depend on how many candidates ran before it,
     * so shuffling the wardrobe would change the advice.
     */
    @Test
    fun `the order of the wardrobe does not change the advice`() {
        val forwards = analyzeGaps(context(shoelessWardrobe))
        val backwards = analyzeGaps(context(shoelessWardrobe.reversed()))

        assertEquals(forwards.map { it.slot }, backwards.map { it.slot })
        assertEquals(forwards.map { it.want }, backwards.map { it.want })
    }

    // --- Guards ---------------------------------------------------------------

    @Test
    fun `a wardrobe too small to reason about is told nothing`() {
        val tiny = shoelessWardrobe.take(MIN_WARDROBE_FOR_GAPS - 1)

        assertTrue(analyzeGaps(context(tiny)).isEmpty())
    }

    @Test
    fun `retired garments do not count towards the size guard`() {
        val mostlyRetired = shoelessWardrobe.mapIndexed { index, garment ->
            if (index >= MIN_WARDROBE_FOR_GAPS - 1) garment.copy(isAvailable = false) else garment
        }

        assertTrue(analyzeGaps(context(mostlyRetired)).isEmpty())
    }

    @Test
    fun `at most one gap per slot however many occasions want one`() {
        val gaps = analyzeGaps(context(shoelessWardrobe), GapOptions(gaps = 5, occasions = 5))

        assertEquals(gaps.map { it.slot }.distinct().size, gaps.size)
    }

    @Test
    fun `no more gaps are reported than were asked for`() {
        val gaps = analyzeGaps(context(shoelessWardrobe), GapOptions(gaps = 1, occasions = 5))

        assertTrue(gaps.size <= 1)
    }

    // --- What it actually says ------------------------------------------------

    @Test
    fun `a wardrobe with no shoes is told about shoes`() {
        val gaps = analyzeGaps(context(shoelessWardrobe))

        assertTrue(gaps.isNotEmpty(), "twelve garments and no shoes is a gap")
        assertTrue(
            gaps.any { it.slot == OutfitSlot.SHOES },
            "expected shoes among ${gaps.map { it.slot }}",
        )
    }

    /**
     * The failure this feature has when it is built the obvious way. Ranked purely
     * by coverage, the top two gaps in this wardrobe were a cocktail dress and a
     * track suit -- it owns nothing formal and nothing sporting, so both scored
     * zero -- while the twelve casual clothes with no shoes to wear went
     * unmentioned.
     */
    @Test
    fun `an occasion the wardrobe does not dress for is left alone`() {
        val gaps = analyzeGaps(context(shoelessWardrobe), GapOptions(gaps = 5, occasions = 5))

        for (gap in gaps) {
            assertTrue(
                gap.occasion in listOf(Occasion.CASUAL, Occasion.WORK),
                "this wardrobe owns nothing for ${gap.occasion}, so it has no gap there",
            )
        }
    }

    @Test
    fun `nothing fills the slot is the evidence when nothing does`() {
        val shoes = analyzeGaps(context(shoelessWardrobe)).first { it.slot == OutfitSlot.SHOES }

        assertEquals(GapEvidence.NOTHING_FITS, shoes.evidence)
        assertNull(shoes.replaces)
    }

    @Test
    fun `the count promised matches what coverage says`() {
        for (gap in analyzeGaps(context(shoelessWardrobe))) {
            val coverage = outfitCoverage(shoelessWardrobe, gap.occasion, gap.season)
            val lift = coverage.slotLift.first { it.slot == gap.slot }

            assertEquals(lift.outfits, gap.outfitsUnlocked)
        }
    }

    @Test
    fun `what it suggests could actually fill the slot it suggests it for`() {
        for (gap in analyzeGaps(context(shoelessWardrobe))) {
            val probe = garment("probe", gap.want.category, gap.want.subcategory)

            assertTrue(
                garmentSlots(probe).contains(gap.slot),
                "${gap.want} was suggested for the ${gap.slot} slot and does not fill it",
            )
            assertTrue(
                probe.occasions().contains(gap.occasion),
                "${gap.want} was suggested for a ${gap.occasion} day and is not dressed for one",
            )
        }
    }

    @Test
    fun `it never suggests a garment already owned in that colour`() {
        for (gap in analyzeGaps(context(shoelessWardrobe), GapOptions(gaps = 5, occasions = 5))) {
            val type = GarmentType(gap.want.category, gap.want.subcategory ?: "")

            assertFalse(
                alreadyOwned(type, gap.want.colorPrimary, shoelessWardrobe),
                "${gap.want} is already in the wardrobe",
            )
        }
    }

    @Test
    fun `it suggests a colour the wardrobe already wears`() {
        val anchors = colorAnchors(shoelessWardrobe, GapOptions().colourAnchors)

        for (gap in analyzeGaps(context(shoelessWardrobe))) {
            assertTrue(
                anchors.contains(gap.want.colorPrimary),
                "${gap.want.colorPrimary} is not one of this wardrobe's colours: $anchors",
            )
        }
    }

    /**
     * The examples are the case for the gap, so they have to be outfits somebody
     * owns -- every garment in them real, and the phantom itself never presented
     * as something already hanging up.
     */
    @Test
    fun `every example is made of garments the wardrobe holds`() {
        val owned = shoelessWardrobe.map { it.id }.toSet()

        for (gap in analyzeGaps(context(shoelessWardrobe))) {
            for (example in gap.examples) {
                val phantoms = example.garments.filter { it.id == PHANTOM_GARMENT_ID }
                assertEquals(1, phantoms.size, "an example must contain the gap exactly once")

                for (garment in example.garments - phantoms.toSet()) {
                    assertTrue(garment.id in owned, "${garment.id} is not in this wardrobe")
                }
            }
        }
    }

    @Test
    fun `an example never carries more examples than asked for`() {
        val gaps = analyzeGaps(context(shoelessWardrobe), GapOptions(examplesPerGap = 2))

        for (gap in gaps) assertTrue(gap.examples.size <= 2)
    }

    // --- The retired case ----------------------------------------------------

    @Test
    fun `a retired garment nothing replaced is named`() {
        val withRetiredShoes = shoelessWardrobe +
            garment("shoes-gone", "shoes", "Loafers", color = "#000000", available = false)

        val shoes = analyzeGaps(context(withRetiredShoes)).first { it.slot == OutfitSlot.SHOES }

        assertEquals(GapEvidence.RETIRED_UNREPLACED, shoes.evidence)
        assertEquals("shoes-gone", assertNotNull(shoes.replaces).id)
    }

    @Test
    fun `a retired garment that was replaced is not named`() {
        val replaced = shoelessWardrobe +
            garment("shoes-gone", "shoes", "Loafers", color = "#000000", available = false) +
            garment("shoes-new", "shoes", "Loafers", color = "#6B4423")

        val gaps = analyzeGaps(context(replaced))

        assertTrue(
            gaps.none { it.evidence == GapEvidence.RETIRED_UNREPLACED },
            "the slot is filled again, so nothing was left unreplaced",
        )
    }

    // --- Raising the bar -----------------------------------------------------

    /**
     * A wardrobe with nothing empty can still have a gap, and this is the only
     * case where the examples do the work: with every slot filled the arithmetic
     * has nothing to say, so a candidate is reported only if it out-scores what
     * the suggestions screen is already offering.
     */
    @Test
    fun `a full wardrobe is only told about something better than what it has`() {
        val complete = shoelessWardrobe +
            garment("shoes-black", "shoes", "Loafers", color = "#000000") +
            garment("shoes-white", "shoes", "Sneakers", color = "#FFFFFF") +
            garment("dress-navy", "dresses", "Midi", color = "#1F3A93")

        for (gap in analyzeGaps(context(complete))) {
            if (gap.evidence != GapEvidence.RAISES_THE_BAR) continue

            assertTrue(
                gap.examples.isNotEmpty(),
                "${gap.want} raises the bar over nothing in particular",
            )
        }
    }

    // --- Colour anchors ------------------------------------------------------

    @Test
    fun `anchors are the wardrobe's own colours, commonest first`() {
        val wardrobe = listOf(
            garment("a", "tops", "Shirt", color = "#1F3A93"),
            garment("b", "tops", "Shirt", color = "#1F3A93"),
            garment("c", "tops", "Shirt", color = "#1F3A93"),
            garment("d", "bottoms", "Jeans", color = "#FFFFFF"),
        )

        assertEquals(listOf("#1F3A93", "#FFFFFF"), colorAnchors(wardrobe, 6))
    }

    @Test
    fun `anchors do not spend themselves on shades of the same colour`() {
        val nearlyAllBlack = listOf(
            garment("a", "tops", "Shirt", color = "#000000"),
            garment("b", "tops", "Shirt", color = "#010101"),
            garment("c", "tops", "Shirt", color = "#020202"),
            garment("d", "bottoms", "Jeans", color = "#1F3A93"),
        )

        val anchors = colorAnchors(nearlyAllBlack, 6)

        assertEquals(2, anchors.size, "three blacks are one colour: $anchors")
        assertTrue(anchors.contains("#1F3A93"))
    }

    @Test
    fun `multicoloured is never something to go and look for`() {
        val wardrobe = listOf(
            garment("a", "tops", "Shirt", color = MULTI_COLOR),
            garment("b", "tops", "Shirt", color = MULTI_COLOR),
            garment("c", "bottoms", "Jeans", color = "#1F3A93"),
        )

        assertEquals(listOf("#1F3A93"), colorAnchors(wardrobe, 6))
    }

    @Test
    fun `a wardrobe of unreadable colours is told nothing rather than nonsense`() {
        val unreadable = shoelessWardrobe.map { it.copy(colorPrimary = MULTI_COLOR, colorPalette = emptyList()) }

        assertTrue(analyzeGaps(context(unreadable)).isEmpty())
    }

    // --- The phantom itself --------------------------------------------------

    @Test
    fun `a phantom carries the season its type implies`() {
        val sandals = PhantomGarment("shoes", "Sandals", "#FFFFFF").asGarment()

        assertEquals(listOf(Season.SUMMER.tag), sandals.tags)
        assertFalse(matchesSeason(sandals, Season.WINTER, listOf(Season.WINTER)))
    }

    @Test
    fun `a phantom is dressed for the occasions its type is`() {
        val loafers = PhantomGarment("shoes", "Loafers", "#000000").asGarment()

        assertEquals(listOf(Occasion.CASUAL, Occasion.WORK), loafers.occasions())
    }

    /**
     * Ties are the normal case in a wardrobe with nothing rated, and reporting one
     * arbitrarily chosen type as the answer would be reporting the order of the
     * catalogue as advice.
     */
    @Test
    fun `types that would do just as well are named as alternatives`() {
        val shoes = analyzeGaps(context(shoelessWardrobe)).first { it.slot == OutfitSlot.SHOES }

        assertTrue(
            shoes.alternatives.isNotEmpty(),
            "nothing has been rated, so the work shoe types cannot be told apart",
        )
        for (alternative in shoes.alternatives) {
            assertTrue(
                alternative.subcategory != shoes.want.subcategory,
                "the winner is not one of its own alternatives",
            )
            val probe = garment("probe", alternative.category, alternative.subcategory)
            assertTrue(garmentSlots(probe).contains(OutfitSlot.SHOES))
        }
        assertEquals(
            shoes.alternatives.map { it.subcategory },
            shoes.alternatives.map { it.subcategory }.distinct(),
            "one type in two colours is one alternative",
        )
    }

    /**
     * The bug that left the strongest gap with nothing to show for itself:
     * `buildSuggestions` keeps only the templates it has a garment for every slot
     * of, and reads that from the pool rather than from the seed -- so a shoe
     * seeded into a shoeless wardrobe made every shoe template unviable.
     */
    @Test
    fun `the gap in an empty slot still comes with outfits to show`() {
        val shoes = analyzeGaps(context(shoelessWardrobe)).first { it.slot == OutfitSlot.SHOES }

        assertTrue(shoes.examples.isNotEmpty(), "36 outfits promised and none shown")
        for (example in shoes.examples) {
            assertTrue(
                example.garments.any { it.id == PHANTOM_GARMENT_ID },
                "an outfit offered as proof of a gap has to contain the gap",
            )
        }
    }

    @Test
    fun `a phantom in winter is not a summer garment`() {
        val gaps = analyzeGaps(context(shoelessWardrobe, season = Season.WINTER))
        val shoes = gaps.firstOrNull { it.slot == OutfitSlot.SHOES } ?: return

        assertFalse(
            shoes.want.subcategory == "Sandals",
            "sandals carry a summer tag and should lose to a boot in December",
        )
    }
}
