package com.wardrobapp.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which season a month belongs to.
 *
 * The suggestion engine falls back to this whenever no season has been picked,
 * so it decides what a wardrobe is judged against most of the time -- and the
 * boundaries are the kind of thing that is wrong silently. The TypeScript's
 * version was mocked in its own tests and never checked directly.
 */
class SeasonOfMonthTest {

    @Test
    fun `runs March to May as spring`() {
        assertEquals(listOf(Season.SPRING, Season.SPRING, Season.SPRING), listOf(2, 3, 4).map(::seasonOfMonth))
    }

    @Test
    fun `runs June to August as summer`() {
        assertEquals(listOf(Season.SUMMER, Season.SUMMER, Season.SUMMER), listOf(5, 6, 7).map(::seasonOfMonth))
    }

    @Test
    fun `runs September to November as fall`() {
        assertEquals(listOf(Season.FALL, Season.FALL, Season.FALL), listOf(8, 9, 10).map(::seasonOfMonth))
    }

    @Test
    fun `wraps December to February as winter`() {
        assertEquals(listOf(Season.WINTER, Season.WINTER, Season.WINTER), listOf(11, 0, 1).map(::seasonOfMonth))
    }

    @Test
    fun `never says all-season, and never fails`() {
        // ALL_SEASON is something a garment can be, not a time of year -- and an
        // out-of-range month must not throw: this is called with whatever the
        // platform's calendar hands over.
        val everyMonth = (-1..12).map(::seasonOfMonth)

        assertEquals(4, everyMonth.toSet().size)
        assertEquals(false, everyMonth.contains(Season.ALL_SEASON))
    }
}
