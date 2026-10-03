package com.wardrobapp.data

import com.wardrobapp.domain.GapEvidence
import com.wardrobapp.domain.GapOptions
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.OutfitSlot
import com.wardrobapp.domain.Season
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Loading what the gap analysis needs, then running it.
 *
 * The analysis itself is covered in :domain. What is checked here is the part
 * only this class decides: that retired garments reach it rather than being
 * filtered out on the way, which retired garment gets named when several would
 * do, that a run is repeatable, and that a gap arrives with the photographs a
 * screen needs and with the hole still in it.
 */
class GapsTest {

    private val driver = JdbcSqlDriver.fresh()
    private val imageDirectory = "file:///photos/"
    private val subject = Gaps(
        GarmentQueries(driver, imageDirectory),
        OutfitQueries(driver),
    )

    @AfterTest
    fun close() = driver.close()

    private fun addGarment(
        id: String,
        category: String,
        subcategory: String,
        color: String = "#000000",
        available: Boolean = true,
        unavailableDate: String? = null,
    ) {
        driver.execute(
            "INSERT INTO garments (id, image_uri, image_uris, category, subcategory, " +
                "subcategories, tags, color_primary, color_palette, is_available, " +
                "unavailable_date, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(
                id, "$id.jpg", "[\"$id.jpg\"]", category, subcategory,
                "[\"$subcategory\"]", "[]", color, "[\"$color\"]",
                if (available) 1 else 0, unavailableDate, "2026-01-01", "2026-01-01",
            ),
        )
    }

    /**
     * Twelve garments and no shoes: over the size the analysis will speak at, and
     * varied enough in colour that the anchors are not all one shade of black.
     * The same wardrobe the domain tests use, written into a database.
     */
    private fun givenAShoelessWardrobe() {
        addGarment("top-white", "tops", "T-Shirt", "#FFFFFF")
        addGarment("top-navy", "tops", "Shirt", "#1F3A93")
        addGarment("top-grey", "tops", "Shirt", "#7F8C8D")
        addGarment("top-red", "tops", "Blouse", "#C0392B")
        addGarment("bottom-jeans", "bottoms", "Jeans", "#2C3E50")
        addGarment("bottom-chinos", "bottoms", "Chinos", "#BDC3C7")
        addGarment("bottom-black", "bottoms", "Pants", "#000000")
        addGarment("skirt-navy", "bottoms", "Skirt", "#1F3A93")
        addGarment("coat-camel", "outerwear", "Coat", "#B8860B")
        addGarment("cardigan-grey", "outerwear", "Cardigan", "#7F8C8D")
        addGarment("hat-black", "accessories", "Hat", "#000000")
        addGarment("bag-brown", "accessories", "Bag", "#6B4423")
    }

    @Test
    fun `finds nothing in an empty wardrobe`() {
        assertEquals(emptyList(), subject.analyze(Season.FALL))
    }

    @Test
    fun `finds the shoes a wardrobe with none is missing`() {
        givenAShoelessWardrobe()

        val gaps = subject.analyze(Season.FALL)

        assertTrue(gaps.isNotEmpty(), "twelve garments and no shoes found no gap")
        assertTrue(
            gaps.any { it.gap.slot == OutfitSlot.SHOES },
            "expected shoes among ${gaps.map { it.gap.slot }}",
        )
    }

    /**
     * The reason this class loads retired garments at all. Filtered out on the way
     * in -- which is what [Suggestions] correctly does -- the strongest evidence
     * the analysis has could never be found.
     */
    @Test
    fun `a retired garment reaches the analysis`() {
        givenAShoelessWardrobe()
        addGarment(
            "shoes-worn-out", "shoes", "Loafers",
            available = false, unavailableDate = "2026-03-01T00:00:00.000Z",
        )

        val shoes = subject.analyze(Season.FALL).first { it.gap.slot == OutfitSlot.SHOES }

        assertEquals(GapEvidence.RETIRED_UNREPLACED, shoes.gap.evidence)
        assertEquals("shoes-worn-out", assertNotNull(shoes.replaces).id)
    }

    /**
     * The one thing the ordering in `wardrobe()` is load-bearing for. A domain
     * garment carries no dates, so the analysis names whichever retired garment it
     * meets first -- and only this layer can see `unavailable_date` to decide
     * which that should be.
     */
    @Test
    fun `the most recently retired garment is the one named`() {
        givenAShoelessWardrobe()
        addGarment(
            "shoes-old", "shoes", "Loafers",
            available = false, unavailableDate = "2024-01-01T00:00:00.000Z",
        )
        // Both types are dressed for work and for casual, so whichever occasion
        // the gap lands on, the date is what decides between them and not the
        // occasion derivation. Boots here made this test pass for the wrong
        // reason: they are casual only, so a work gap skipped them on merit.
        addGarment(
            "shoes-recent", "shoes", "Flats",
            available = false, unavailableDate = "2026-06-01T00:00:00.000Z",
        )

        val shoes = subject.analyze(Season.FALL).first { it.gap.slot == OutfitSlot.SHOES }

        assertEquals("shoes-recent", assertNotNull(shoes.replaces).id)
    }

    @Test
    fun `a garment retired before the date column existed does not outrank one with a date`() {
        givenAShoelessWardrobe()
        addGarment("shoes-undated", "shoes", "Flats", available = false)
        addGarment(
            "shoes-dated", "shoes", "Loafers",
            available = false, unavailableDate = "2020-01-01T00:00:00.000Z",
        )

        val shoes = subject.analyze(Season.FALL).first { it.gap.slot == OutfitSlot.SHOES }

        assertEquals("shoes-dated", assertNotNull(shoes.replaces).id)
    }

    @Test
    fun `a slot filled again names nobody`() {
        givenAShoelessWardrobe()
        addGarment(
            "shoes-old", "shoes", "Loafers",
            available = false, unavailableDate = "2024-01-01T00:00:00.000Z",
        )
        addGarment("shoes-new", "shoes", "Loafers", "#6B4423")

        for (gap in subject.analyze(Season.FALL)) {
            assertNull(gap.replaces, "${gap.gap.slot} named a replacement for a filled slot")
        }
    }

    /**
     * The engine works on the domain type, which has no photo at all. A gap that
     * reached the screen that way would draw blank tiles for the outfits that are
     * its whole argument.
     */
    @Test
    fun `an example arrives with the photos a screen needs`() {
        givenAShoelessWardrobe()

        val shoes = subject.analyze(Season.FALL).first { it.gap.slot == OutfitSlot.SHOES }

        assertTrue(shoes.examples.isNotEmpty(), "outfits promised and none shown")
        for (example in shoes.examples) {
            for (garment in example.garments.filterNotNull()) {
                assertTrue(
                    garment.displayImage.startsWith(imageDirectory),
                    "${garment.id} came back without a resolved photo",
                )
            }
        }
    }

    /**
     * The hole keeps its place. Dropped instead, an example would look like an
     * outfit the reader could already put on, which is the opposite of the claim.
     */
    @Test
    fun `the garment that does not exist yet comes through as a hole`() {
        givenAShoelessWardrobe()

        val shoes = subject.analyze(Season.FALL).first { it.gap.slot == OutfitSlot.SHOES }

        for (example in shoes.examples) {
            assertEquals(
                1,
                example.garments.count { it == null },
                "an example must have exactly one hole in it: ${example.garments}",
            )
        }
    }

    /**
     * Advice, not a shuffle. The suggestions screen wants a different answer on a
     * second tap; this one must not give one, or somebody checking what it said
     * would be told something else.
     */
    @Test
    fun `the same wardrobe is told the same thing twice running`() {
        givenAShoelessWardrobe()

        val first = subject.analyze(Season.FALL)
        val second = subject.analyze(Season.FALL)

        assertEquals(first.map { it.gap.want }, second.map { it.gap.want })
        assertEquals(first.map { it.gap.slot }, second.map { it.gap.slot })
        assertEquals(
            first.map { example -> example.examples.map { it.garments.map { g -> g?.id } } },
            second.map { example -> example.examples.map { it.garments.map { g -> g?.id } } },
        )
    }

    @Test
    fun `coverage counts what the wardrobe can finish without sampling anything`() {
        givenAShoelessWardrobe()

        val casualFall = subject.coverage()
            .first { it.occasion == Occasion.CASUAL && it.season == Season.FALL }

        assertTrue(casualFall.outfits > 0, "a wardrobe of twelve finishes no casual outfit")
        assertTrue(
            casualFall.emptySlots.contains(OutfitSlot.SHOES),
            "shoes are missing and coverage did not say so",
        )
    }

    @Test
    fun `coverage counts only what can still be worn`() {
        addGarment("top", "tops", "T-Shirt")
        addGarment(
            "bottom", "bottoms", "Jeans",
            available = false, unavailableDate = "2026-01-01T00:00:00.000Z",
        )

        val casual = subject.coverage()
            .first { it.occasion == Occasion.CASUAL && it.season == Season.FALL }

        assertEquals(0L, casual.outfits)
        assertTrue(casual.emptySlots.contains(OutfitSlot.BOTTOMS))
    }

    @Test
    fun `the caps it is given are the caps it honours`() {
        givenAShoelessWardrobe()

        val gaps = subject.analyze(Season.FALL, GapOptions(gaps = 1, examplesPerGap = 1))

        assertTrue(gaps.size <= 1)
        for (gap in gaps) assertTrue(gap.examples.size <= 1)
    }
}
