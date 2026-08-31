package com.wardrobapp.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage counting.
 *
 * Two kinds of test here, and the first kind matters more. The properties the
 * closed-form arithmetic *rests* on are asserted directly -- distinct template
 * slot sets, one slot per garment -- because a template added later with a
 * duplicate slot set would not break any example below, it would silently
 * overcount every wardrobe in the world. The rest are worked examples small
 * enough to count by hand.
 */
class WardrobeCoverageTest {

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

    /** Two tops and two bottoms: enough for exactly one template. */
    private val topsAndBottoms = listOf(
        garment("top-tee", "tops", "T-Shirt"),
        garment("top-shirt", "tops", "Shirt"),
        garment("bottom-jeans", "bottoms", "Jeans"),
        garment("bottom-chinos", "bottoms", "Chinos"),
    )

    // --- The properties the arithmetic depends on -------------------------------

    @Test
    fun `no two templates call for the same set of slots`() {
        val slotSets = OUTFIT_TEMPLATES.map { it.toSet() }

        assertEquals(
            slotSets.size,
            slotSets.distinct().size,
            "Two templates with the same slots would be counted as two different " +
                "outfits for the same garments, inflating every coverage total.",
        )
    }

    @Test
    fun `a template never calls for the same slot twice`() {
        for (template in OUTFIT_TEMPLATES) {
            assertEquals(
                template.size,
                template.distinct().size,
                "A repeated slot makes a template's count a permutation problem " +
                    "rather than a product: $template",
            )
        }
    }

    @Test
    fun `every catalogue type fills at most one slot`() {
        for (category in GARMENT_CATEGORIES) {
            for (subcategory in category.subcategories) {
                val probe = garment("probe", category.id, subcategory)
                assertTrue(
                    garmentSlots(probe).size <= 1,
                    "${category.id}/$subcategory fills ${garmentSlots(probe)}, and a " +
                        "garment in two slots of one template breaks the product.",
                )
            }
        }
    }

    // --- Worked examples -------------------------------------------------------

    @Test
    fun `tops and bottoms complete only the templates they can fill`() {
        val coverage = outfitCoverage(topsAndBottoms, Occasion.CASUAL, Season.SPRING)

        // Two tops times two bottoms, through the one template that asks for
        // nothing else.
        assertEquals(4L, coverage.outfits)
        assertFalse(coverage.isBare)
    }

    @Test
    fun `an empty wardrobe is bare for every kind of day`() {
        for (coverage in coverageGrid(emptyList())) {
            assertEquals(0L, coverage.outfits)
            assertTrue(coverage.isBare)
        }
    }

    /**
     * A tie rather than a winner, and deliberately so: with tops and bottoms and
     * nothing else, one pair of shoes and one jacket each complete the same four
     * outfits. Asserting that shoes lead on their own would be asserting that
     * `templateWeight` had leaked into the counting.
     */
    @Test
    fun `shoes and a layer are the biggest gaps in a wardrobe with neither`() {
        val coverage = outfitCoverage(topsAndBottoms, Occasion.CASUAL, Season.SPRING)

        val best = coverage.slotLift.first().outfits
        val leaders = coverage.slotLift.filter { it.outfits == best }.map { it.slot }

        assertEquals(4L, best)
        assertTrue(leaders.contains(OutfitSlot.SHOES), "led by $leaders")
        assertTrue(leaders.contains(OutfitSlot.OUTERWEAR), "led by $leaders")
        assertTrue(coverage.emptySlots.contains(OutfitSlot.SHOES))
    }

    /**
     * The bug the lift test caught: `[ACTIVEWEAR_SETS]` is a template that needs
     * nothing else, so its lift came out as an unconditional 1 -- promising a
     * casual outfit that adding a track suit could never deliver, because a track
     * suit is dressed for sport and lounge.
     */
    @Test
    fun `lift never promises a slot the occasion cannot use`() {
        for (coverage in coverageGrid(topsAndBottoms)) {
            for (promised in coverage.slotLift) {
                assertTrue(
                    typesFor(promised.slot, coverage.occasion).isNotEmpty(),
                    "${promised.slot} was promised ${promised.outfits} outfits for a " +
                        "${coverage.occasion} day, and no garment type can fill it.",
                )
            }
        }
    }

    /**
     * The claim the whole feature makes, checked against the thing it claims
     * about: adding exactly one garment to a slot must add exactly as many
     * outfits as the lift figure promised.
     */
    @Test
    fun `lift predicts what one more garment actually adds`() {
        val before = outfitCoverage(topsAndBottoms, Occasion.CASUAL, Season.SPRING)

        for (promised in before.slotLift) {
            val type = typesFor(promised.slot, Occasion.CASUAL).first()
            val after = outfitCoverage(
                topsAndBottoms + garment("new", type.category, type.subcategory),
                Occasion.CASUAL,
                Season.SPRING,
            )

            assertEquals(
                promised.outfits,
                after.outfits - before.outfits,
                "Adding one ${type.category}/${type.subcategory} was supposed to add " +
                    "${promised.outfits} outfits in the ${promised.slot} slot.",
            )
        }
    }

    @Test
    fun `a retired garment completes nothing`() {
        val retired = topsAndBottoms.map {
            if (it.category == "bottoms") it.copy(isAvailable = false) else it
        }

        val coverage = outfitCoverage(retired, Occasion.CASUAL, Season.SPRING)

        assertTrue(coverage.isBare)
        assertTrue(coverage.emptySlots.contains(OutfitSlot.BOTTOMS))
    }

    @Test
    fun `a garment tagged for the opposite season does not count`() {
        val winterOnly = listOf(
            garment("top-tee", "tops", "T-Shirt"),
            garment("bottom-wool", "bottoms", "Pants", tags = listOf("winter")),
        )

        assertEquals(1L, outfitCoverage(winterOnly, Occasion.CASUAL, Season.WINTER).outfits)
        assertEquals(0L, outfitCoverage(winterOnly, Occasion.CASUAL, Season.SUMMER).outfits)
    }

    @Test
    fun `a garment dressed for one occasion does not count for another`() {
        // Sweatpants are lounge and casual; nothing about them is work.
        val loungewear = listOf(
            garment("top-shirt", "tops", "Shirt"),
            garment("bottom-sweats", "bottoms", "Sweatpants"),
        )

        assertEquals(1L, outfitCoverage(loungewear, Occasion.CASUAL, Season.FALL).outfits)
        assertEquals(0L, outfitCoverage(loungewear, Occasion.WORK, Season.FALL).outfits)
    }

    @Test
    fun `a work day is never reported as missing a loungewear set`() {
        val coverage = outfitCoverage(emptyList(), Occasion.WORK, Season.FALL)

        assertFalse(
            coverage.emptySlots.contains(OutfitSlot.LOUNGEWEAR_SETS),
            "No type that fills that slot is dressed for work, so its being empty " +
                "is the catalogue behaving rather than a gap.",
        )
        assertTrue(coverage.emptySlots.contains(OutfitSlot.TOPS))
    }

    @Test
    fun `a sport day is never reported as missing a dress`() {
        val coverage = outfitCoverage(emptyList(), Occasion.SPORT, Season.SUMMER)

        assertFalse(coverage.emptySlots.contains(OutfitSlot.DRESSES))
    }

    @Test
    fun `all-season reads as no seasonal constraint`() {
        val winterOnly = listOf(
            garment("top-tee", "tops", "T-Shirt"),
            garment("bottom-wool", "bottoms", "Pants", tags = listOf("winter")),
        )

        assertEquals(1L, outfitCoverage(winterOnly, Occasion.CASUAL, Season.ALL_SEASON).outfits)
    }

    @Test
    fun `the grid reports the worst kind of day first`() {
        val grid = coverageGrid(topsAndBottoms)

        assertEquals(
            grid.map { it.outfits }.sorted(),
            grid.map { it.outfits },
        )
        assertEquals(Occasion.entries.size * REAL_SEASONS.size, grid.size)
    }

    @Test
    fun `every slot a template calls for can be filled by something`() {
        val fillable = OUTFIT_TEMPLATES.flatten().toSet()

        for (slot in fillable) {
            assertTrue(
                TYPES_BY_SLOT[slot]?.isNotEmpty() == true,
                "$slot is called for by a template but no catalogue type fills it, " +
                    "so that template can never be completed by anybody.",
            )
        }
    }
}
