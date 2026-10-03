package com.wardrobapp.presentation

import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two chip rows above the outfit suggestions.
 *
 * They behave differently on purpose: seasons are a set, because a garment for
 * spring is often a garment for fall, and occasion is one choice, because an
 * outfit is for one thing at a time. The rule both rows share is that every state
 * can be got back out of -- there is no combination of taps that leaves someone
 * stuck with a filter they cannot clear.
 */
class OutfitFiltersTest {

    @Test
    fun `nothing chosen is the unfiltered state, and the any chips are the ones lit`() {
        val filters = OutfitFilters()

        assertTrue(filters.isUnfiltered)
        assertEquals(FilterChip<Season>(null, true), filters.seasonChips().first())
        assertEquals(FilterChip<Occasion>(null, true), filters.occasionChips().first())
        // One chip per season and occasion, plus the "any" chip in front.
        assertEquals(Season.entries.size + 1, filters.seasonChips().size)
        assertEquals(Occasion.entries.size + 1, filters.occasionChips().size)
    }

    @Test
    fun `tapping a season turns it on, and tapping it again turns it off`() {
        val one = OutfitFilters().withSeasonToggled(Season.SUMMER)
        assertEquals(listOf(Season.SUMMER), one.seasons)
        assertTrue(!one.isUnfiltered)

        val off = one.withSeasonToggled(Season.SUMMER)
        assertEquals(emptyList(), off.seasons)
        assertTrue(off.isUnfiltered, "emptying the row by tapping did not reach the unfiltered state")
    }

    @Test
    fun `seasons are kept in the app's own order, not the order they were tapped`() {
        // So the same choice always reads the same, and matches what the chips
        // show.
        val filters = OutfitFilters()
            .withSeasonToggled(Season.WINTER)
            .withSeasonToggled(Season.SPRING)

        assertEquals(Season.entries.filter { it in filters.seasons }, filters.seasons)
    }

    @Test
    fun `any clears the seasons rather than being one of them`() {
        val filters = OutfitFilters(seasons = listOf(Season.SUMMER, Season.WINTER))

        val cleared = filters.withSeasonToggled(null)

        assertEquals(emptyList(), cleared.seasons)
        assertEquals(FilterChip<Season>(null, true), cleared.seasonChips().first())
    }

    @Test
    fun `an occasion is one choice, and choosing another replaces it`() {
        val work = OutfitFilters().withOccasionSelected(Occasion.WORK)
        assertEquals(Occasion.WORK, work.occasion)

        val formal = work.withOccasionSelected(Occasion.FORMAL)
        assertEquals(Occasion.FORMAL, formal.occasion)
    }

    @Test
    fun `tapping the chosen occasion clears it, the same as tapping any`() {
        val work = OutfitFilters().withOccasionSelected(Occasion.WORK)

        assertEquals(null, work.withOccasionSelected(Occasion.WORK).occasion)
        assertEquals(null, work.withOccasionSelected(null).occasion)
    }

    @Test
    fun `the chips say what is on`() {
        val filters = OutfitFilters(seasons = listOf(Season.SUMMER), occasion = Occasion.WORK)

        val seasons = filters.seasonChips()
        assertEquals(FilterChip<Season>(null, false), seasons.first())
        assertEquals(listOf(Season.SUMMER), seasons.filter { it.active }.map { it.value })

        val occasions = filters.occasionChips()
        assertEquals(FilterChip<Occasion>(null, false), occasions.first())
        assertEquals(listOf(Occasion.WORK), occasions.filter { it.active }.map { it.value })
    }
}
