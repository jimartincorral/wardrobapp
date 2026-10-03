package com.wardrobapp.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Rewriting a garment's photo slots when a background is removed or put back.
 *
 * Worth testing away from the screen because both mistakes are quiet: a
 * misaligned cut-out puts it on the wrong photo, and a wrongly-discarded file
 * deletes a photo the garment still points at. Neither throws.
 *
 * The shapes here are the ones the app actually produces -- a collapsed slot
 * where both columns hold the cut-out, and a one-element cut-out column standing
 * in for photos that predate it.
 */
class BackgroundEditTest {

    // ---- removing ------------------------------------------------------------

    @Test
    fun `the cut-out replaces the photo in both columns`() {
        val edit = withBackgroundRemovedAt(listOf("a.jpg"), listOf(""), 0, "cut.png")!!

        assertEquals(listOf("cut.png"), edit.images)
        assertEquals(listOf("cut.png"), edit.cutouts)
        assertEquals("a.jpg", edit.discardable)
    }

    @Test
    fun `only the chosen slot changes`() {
        val edit = withBackgroundRemovedAt(
            images = listOf("a.jpg", "b.jpg", "c.jpg"),
            cutouts = listOf("", "", ""),
            index = 1,
            cutout = "cut.png",
        )!!

        assertEquals(listOf("a.jpg", "cut.png", "c.jpg"), edit.images)
        assertEquals(listOf("", "cut.png", ""), edit.cutouts)
        assertEquals("b.jpg", edit.discardable)
    }

    @Test
    fun `a short cut-out column is padded before being written into`() {
        // What a garment saved before the column existed looks like: one blank
        // entry standing for however many photos there are.
        val edit = withBackgroundRemovedAt(
            images = listOf("a.jpg", "b.jpg", "c.jpg"),
            cutouts = listOf(""),
            index = 2,
            cutout = "cut.png",
        )!!

        assertEquals(3, edit.cutouts.size)
        assertEquals(listOf("", "", "cut.png"), edit.cutouts)
        assertEquals("c.jpg", edit.discardable)
    }

    @Test
    fun `removing again discards nothing, because the photo is the cut-out`() {
        // A collapsed slot: both columns already hold the same file. Discarding
        // the "original" here would delete the photo.
        val edit = withBackgroundRemovedAt(
            images = listOf("cut.png"),
            cutouts = listOf("cut.png"),
            index = 0,
            cutout = "cut.png",
        )!!

        assertNull(edit.discardable)
        assertEquals(listOf("cut.png"), edit.images)
    }

    @Test
    fun `an empty slot has nothing to remove`() {
        assertNull(withBackgroundRemovedAt(listOf(""), listOf(""), 0, "cut.png")?.discardable)
    }

    @Test
    fun `a slot that is not there is refused`() {
        assertNull(withBackgroundRemovedAt(listOf("a.jpg"), listOf(""), 3, "cut.png"))
    }

    @Test
    fun `no cut-out means no edit`() {
        assertNull(withBackgroundRemovedAt(listOf("a.jpg"), listOf(""), 0, ""))
    }

    // ---- restoring -----------------------------------------------------------

    @Test
    fun `restoring clears the cut-out and leaves the photo alone`() {
        val edit = withBackgroundRestoredAt(
            images = listOf("a.jpg", "b.jpg"),
            cutouts = listOf("", "cut.png"),
            index = 1,
        )!!

        assertEquals(listOf("a.jpg", "b.jpg"), edit.images)
        assertEquals(listOf("", ""), edit.cutouts)
        assertEquals("cut.png", edit.discardable)
    }

    @Test
    fun `a collapsed slot cannot be restored`() {
        // The original is gone: clearing the cut-out column would leave the
        // garment pointing at a file about to be deleted.
        assertNull(
            withBackgroundRestoredAt(listOf("cut.png"), listOf("cut.png"), 0)
        )
    }

    @Test
    fun `a slot with no cut-out has nothing to restore`() {
        assertNull(withBackgroundRestoredAt(listOf("a.jpg"), listOf(""), 0))
    }

    @Test
    fun `a slot that is not there is refused when restoring`() {
        assertNull(withBackgroundRestoredAt(listOf("a.jpg"), listOf("cut.png"), 7))
    }

    // ---- the two together ----------------------------------------------------

    @Test
    fun `remove then restore is refused, because remove leaves nothing to go back to`() {
        val removed = withBackgroundRemovedAt(listOf("a.jpg"), listOf(""), 0, "cut.png")!!

        // Deliberate, and the same in the React Native app: the cut-out became the
        // only stored image, so backgroundActionFor offers nothing here either.
        assertNull(withBackgroundRestoredAt(removed.images, removed.cutouts, 0))
        assertNull(backgroundActionFor(removed.images[0], removed.cutouts[0]))
    }

    @Test
    fun `the action offered matches what these functions accept`() {
        // A distinct original and cut-out: UNDO, and restoring works.
        assertEquals(BackgroundAction.UNDO, backgroundActionFor("a.jpg", "cut.png"))
        assertEquals("cut.png", withBackgroundRestoredAt(listOf("a.jpg"), listOf("cut.png"), 0)?.discardable)

        // No cut-out: REMOVE, and restoring has nothing to do.
        assertEquals(BackgroundAction.REMOVE, backgroundActionFor("a.jpg", ""))
        assertNull(withBackgroundRestoredAt(listOf("a.jpg"), listOf(""), 0))
    }
}
