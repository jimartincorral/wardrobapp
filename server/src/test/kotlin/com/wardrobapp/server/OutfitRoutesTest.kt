package com.wardrobapp.server

import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import com.wardrobapp.presentation.OutfitDraft
import com.wardrobapp.presentation.OutfitFilters
import com.wardrobapp.presentation.SuggestionRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutfitRoutesTest {

    @Test
    fun `an outfit is made, rated, pinned and deleted`() = serverTest {
        val top = addGarment(category = "tops")
        val trousers = addGarment(category = "bottoms")

        outfitEdit.create(OutfitDraft("Friday", listOf(top.id, trousers.id), Occasion.entries.first(), Season.entries.first()))
        val outfit = outfits.saved(includeArchived = false).outfits.single()
        assertEquals("Friday", outfit.name)
        assertEquals(outfit, outfitEdit.outfit(outfit.id))

        outfitDetail.rate(outfit.id, 4)
        val detail = outfitDetail.outfit(outfit.id)!!
        assertEquals(4, detail.rating)
        assertEquals(listOf(top.id, trousers.id), detail.garments.map { it.id })
        assertEquals(1, home.counts().rated)

        outfits.setPinned(outfit.id, true)
        assertTrue(outfitEdit.outfit(outfit.id)!!.isPinned)

        outfitEdit.update(outfit.id, OutfitDraft("Saturday", listOf(top.id), null, null))
        assertEquals("Saturday", outfitEdit.outfit(outfit.id)!!.name)

        outfitDetail.delete(outfit.id)
        assertNull(outfitDetail.outfit(outfit.id))
        assertNull(outfitEdit.outfit(outfit.id))

        // And back, from either place an outfit is deleted from: the list's
        // undo finds what the detail's delete kept, since the wardrobe has
        // one memory of recent deletes.
        assertTrue(outfits.undoDelete(outfit.id))
        assertEquals(4, outfitDetail.outfit(outfit.id)?.rating)
        assertFalse(outfitDetail.undoDelete(outfit.id))
        outfits.delete(outfit.id)
        assertNull(outfitDetail.outfit(outfit.id))
    }

    @Test
    fun `suggestions are made on the server and become outfits when kept or rated`() = serverTest {
        for (i in 0 until 3) {
            addGarment(category = "tops", colour = "#11223$i")
            addGarment(category = "bottoms", colour = "#44556$i")
            addGarment(category = "shoes", colour = "#77889$i")
        }

        val suggested = outfits.suggest(SuggestionRequest(OutfitFilters(), alreadySeen = emptyList(), seedGarmentId = null, count = 2))
        assertEquals(2, suggested.size, "a wardrobe of tops, bottoms and shoes should suggest something")
        val (kept, rated) = suggested

        outfits.keep(kept)
        assertEquals(listOf(kept.id), outfits.saved(includeArchived = false).outfits.map { it.id })

        // Rating a suggestion stores it archived, so it is there to be rated
        // without appearing among the outfits somebody chose to keep.
        outfits.rate(rated, 2)
        val withArchived = outfits.saved(includeArchived = true)
        assertTrue(withArchived.outfits.single { it.id == rated.id }.isArchived)
        assertEquals(1, withArchived.archivedCount)

        outfits.unarchive(rated.id)
        assertFalse(outfitEdit.outfit(rated.id)!!.isArchived)
    }

    @Test
    fun `statistics are the database's answer, unchanged`() = serverTest {
        addGarment(category = "tops", subcategory = "shirt", colour = "#112233", brand = "Acme")
        addGarment(category = "tops", subcategory = "shirt", colour = "#112233", brand = "Acme")
        val retired = addGarment(category = "bottoms", colour = "#000000")
        garmentDetail.setInUse(retired.id, false)

        assertEquals(wardrobe.statistics.counts(), statistics.counts())
        assertEquals(wardrobe.statistics.duplicates(), statistics.duplicates())
        assertEquals(wardrobe.statistics.gaps(), statistics.gaps())

        assertEquals(2, statistics.counts().inUse)
        assertEquals(1, statistics.duplicates().size)
    }

    @Test
    fun `a missing outfit is null rather than a failure`() = serverTest {
        assertNull(outfitDetail.outfit("no-such-outfit"))
        assertNull(outfitEdit.outfit("no-such-outfit"))
    }
}
