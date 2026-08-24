package com.wardrobapp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which stored photos a tidy-up would touch.
 *
 * No parity fixture: the TypeScript's version of this reads a directory and calls
 * expo-image-manipulator in the same function, so there is nothing in it to dump.
 * These are written from it directly, and they cover the two places it can be
 * wrong quietly -- picking the wrong files, and reporting a saving that did not
 * happen.
 */
class PhotoMaintenanceTest {

    private fun cutout(name: String, width: Int, height: Int, bytes: Long = 1_000) =
        StoredCutout(name, width, height, bytes)

    @Test
    fun `a cut-out inside the cap is left alone`() {
        assertFalse(cutoutNeedsShrinking(cutout("a_nobg.png", 800, 600)))
        assertFalse(cutoutNeedsShrinking(cutout("a_nobg.png", 400, 300)))
        // Exactly at the cap: the boundary is inclusive, so this is already what
        // the app would store.
        assertFalse(cutoutNeedsShrinking(cutout("a_nobg.png", 600, 800)))
    }

    @Test
    fun `a cut-out over the cap needs shrinking, in either direction`() {
        assertTrue(cutoutNeedsShrinking(cutout("a_nobg.png", 2000, 1500)))
        // The case the TypeScript misses: a portrait cut-out narrower than the cap
        // but far taller than it. Scaling by width would leave it 3000 tall.
        assertTrue(cutoutNeedsShrinking(cutout("a_nobg.png", 600, 3000)))
    }

    @Test
    fun `a file whose size cannot be read is left alone`() {
        // A decoder reporting nothing means the file will not open. Rewriting it is
        // the one thing that could turn an unreadable photo into a lost one.
        assertFalse(cutoutNeedsShrinking(cutout("a_nobg.png", 0, 0)))
        assertFalse(cutoutNeedsShrinking(cutout("a_nobg.png", -1, 900)))
    }

    @Test
    fun `only cut-outs are considered`() {
        val files = listOf(
            cutout("photo.jpg", 4000, 3000),
            cutout("photo.png", 4000, 3000),
            cutout("abc_nobg.png", 4000, 3000),
        )

        // An ordinary photo was scaled when it was imported, whatever its
        // dimensions say now -- and a `.png` that is not a cut-out is not this
        // app's file at all.
        assertEquals(listOf("abc_nobg.png"), cutoutsToShrink(files).map { it.name })
    }

    @Test
    fun `the biggest files come first`() {
        val files = listOf(
            cutout("small_nobg.png", 1000, 1000, bytes = 200_000),
            cutout("huge_nobg.png", 1000, 1000, bytes = 9_000_000),
            cutout("middling_nobg.png", 1000, 1000, bytes = 1_000_000),
        )

        // So a pass that is interrupted -- the app killed, the disk full -- has
        // done the most good it could with the time it had.
        assertEquals(
            listOf("huge_nobg.png", "middling_nobg.png", "small_nobg.png"),
            cutoutsToShrink(files).map { it.name },
        )
    }

    @Test
    fun `files of the same size keep a stable order`() {
        // Otherwise two runs over the same wardrobe do the same work in a
        // different order, which makes an interrupted pass unrepeatable.
        val files = listOf(
            cutout("c_nobg.png", 1000, 1000, bytes = 500),
            cutout("a_nobg.png", 1000, 1000, bytes = 500),
            cutout("b_nobg.png", 1000, 1000, bytes = 500),
        )

        assertEquals(
            listOf("a_nobg.png", "b_nobg.png", "c_nobg.png"),
            cutoutsToShrink(files).map { it.name },
        )
    }

    @Test
    fun `nothing to do is not a failure`() {
        assertEquals(cutoutsToShrink(emptyList()), emptyList())
        assertEquals(MaintenanceSummary(0, 0, 0), maintenanceSummary(0, emptyList()))
    }

    @Test
    fun `a summary adds up what was saved`() {
        assertEquals(
            MaintenanceSummary(examined = 12, shrunk = 3, bytesSaved = 600),
            maintenanceSummary(12, listOf(100, 200, 300)),
        )
    }

    @Test
    fun `a file that got bigger still counts as done, and takes nothing off the total`() {
        // Re-encoding a photographic PNG smaller can produce a larger file. The
        // dimensions still came down, which is what the next backup cares about --
        // but a negative saving subtracted from the total would report less saved
        // than actually was.
        assertEquals(
            MaintenanceSummary(examined = 2, shrunk = 2, bytesSaved = 500),
            maintenanceSummary(2, listOf(500, -300)),
        )
    }
}
