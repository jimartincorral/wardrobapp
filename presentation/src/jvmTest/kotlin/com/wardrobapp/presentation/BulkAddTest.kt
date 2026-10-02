package com.wardrobapp.presentation

import com.wardrobapp.domain.Season
import com.wardrobapp.domain.seasonsForSubcategories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules of the bulk-add queue.
 *
 * Two things here are easy to get wrong in ways nobody notices until a wardrobe
 * has been entered twice: a photo silently dropped, and colours landing on the
 * wrong garment because detection finished after the queue moved on. Both are
 * what these cases are about.
 */
class BulkAddTest {

    private fun queueOf(vararg uris: String) = BulkAddState().withDraftsAdded(uris.toList())

    @Test
    fun `a fresh screen has not finished anything`() {
        // Otherwise an untouched screen reports a completed job it never began,
        // and the caller sends the user back to the wardrobe before they picked
        // a single photo.
        val state = BulkAddState()

        assertFalse(state.isFinished)
        assertNull(state.current)
    }

    @Test
    fun `the queue is worked through from the front`() {
        val state = queueOf("a.jpg", "b.jpg", "c.jpg")

        assertEquals("a.jpg", state.current?.imageUri)
        assertEquals("b.jpg", state.advanced().current?.imageUri)
        assertEquals("c.jpg", state.advanced().advanced().current?.imageUri)
        assertTrue(state.advanced().advanced().advanced().isFinished)
    }

    @Test
    fun `the position counts up as the queue drains`() {
        val state = queueOf("a.jpg", "b.jpg", "c.jpg")

        assertEquals(1, state.position)
        assertEquals(2, state.advanced().position)
        assertEquals(3, state.skipped().advanced().position)
    }

    @Test
    fun `more photos join the queue rather than replacing it`() {
        // "Add more" arriving mid-queue must not throw away what is left of the
        // first batch, and the count it is measured against has to grow with it or
        // "3 of 12" starts counting past its own total.
        val state = queueOf("a.jpg", "b.jpg").withDraftsAdded(listOf("c.jpg"))

        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), state.drafts.map { it.imageUri })
        assertEquals(3, state.total)
    }

    @Test
    fun `skipping a photo is not adding a garment`() {
        // Both leave the queue the same way, so the only thing telling them apart
        // is what gets counted -- and the count is what the screen reports at the
        // end.
        val state = queueOf("a.jpg", "b.jpg").skipped().advanced()

        assertEquals(1, state.added)
        assertEquals(1, state.skipped)
    }

    @Test
    fun `an empty queue cannot be advanced past its end`() {
        val drained = queueOf("a.jpg").advanced()

        assertEquals(drained, drained.advanced())
        assertEquals(drained, drained.skipped())
    }

    @Test
    fun `colours land on the photo they were read from`() {
        // Detection is slow enough that the queue moves on while it runs. Keyed on
        // the head, this would paint the next garment with the previous one's
        // colours -- and it would look plausible, which is the worst kind of wrong.
        val state = queueOf("a.jpg", "b.jpg")
            .advanced()
            .withDetectedColors("a.jpg", listOf("#FF0000"))

        assertEquals(listOf(GarmentFormState.DEFAULT_COLOR), state.current?.colorPalette)
    }

    @Test
    fun `colours reach the draft waiting for them`() {
        val state = queueOf("a.jpg", "b.jpg").withDetectedColors("b.jpg", listOf("#00FF00"))

        assertEquals(listOf("#00FF00"), state.drafts[1].colorPalette)
        assertEquals(listOf(GarmentFormState.DEFAULT_COLOR), state.drafts[0].colorPalette)
    }

    @Test
    fun `a photo with nothing countable in it keeps the default`() {
        val state = queueOf("a.jpg").withDetectedColors("a.jpg", emptyList())

        assertEquals(listOf(GarmentFormState.DEFAULT_COLOR), state.current?.colorPalette)
    }

    @Test
    fun `changing the category drops the type that belonged to it`() {
        val state = queueOf("a.jpg")
            .withSubcategoryToggled("Sneakers", ::seasonsForSubcategories)
            .withCategory("bottoms")

        assertEquals(emptyList(), state.current?.subcategories)
        assertEquals("bottoms", state.current?.category)
    }

    @Test
    fun `a type brings its seasons with it`() {
        // A garment catalogued at speed gets no seasons typed in, and a garment
        // with no seasons is one the suggestion engine cannot place. So the type
        // has to imply them here, as it does on the form.
        val state = queueOf("a.jpg").withSubcategoryToggled("Parka", ::seasonsForSubcategories)

        assertTrue(state.current!!.seasons.isNotEmpty())
        assertFalse(state.current!!.seasons.contains(Season.SUMMER))
    }

    @Test
    fun `a re-cropped photo replaces the one it was cropped from`() {
        val state = queueOf("a.jpg", "b.jpg").withPhotoReplaced("a.jpg", "cropped.jpg")

        assertEquals("cropped.jpg", state.current?.imageUri)
        assertEquals("b.jpg", state.drafts[1].imageUri)
    }

    @Test
    fun `cropping drops the cut-out that was of the old framing`() {
        // A cut-out of the uncropped photo no longer describes what is there, and
        // showing it would mean the crop appearing to do nothing.
        val state = queueOf("a.jpg")
            .withCutout("a.jpg", "cut.png")
            .withPhotoReplaced("a.jpg", "cropped.jpg")

        assertEquals("", state.current?.cutoutUri)
        assertEquals("cropped.jpg", state.current?.displayUri)
    }

    @Test
    fun `a cut-out is what gets drawn, until it is undone`() {
        val cut = queueOf("a.jpg").withCutout("a.jpg", "cut.png")

        assertEquals("cut.png", cut.current?.displayUri)
        assertEquals("a.jpg", cut.withCutoutCleared("a.jpg").current?.displayUri)
    }

    @Test
    fun `a cut-out lands on the photo it was cut from`() {
        // Removal is slow enough for the queue to move on underneath it.
        val state = queueOf("a.jpg", "b.jpg").advanced().withCutout("a.jpg", "cut.png")

        assertEquals("", state.current?.cutoutUri)
    }

    @Test
    fun `colours read from a cut-out are ignored once it is undone`() {
        // The answer came back about pixels the draft is no longer showing, and the
        // photo's own colours are not the cut-out's.
        val state = queueOf("a.jpg")
            .withCutout("a.jpg", "cut.png")
            .withCutoutCleared("a.jpg")
            .withDetectedColors("cut.png", listOf("#FF0000"))

        assertEquals(listOf(GarmentFormState.DEFAULT_COLOR), state.current?.colorPalette)
    }

    @Test
    fun `a cut-out is stored in both columns and the original let go`() {
        // The form's rule, reached through a draft: saving space is the whole point
        // of removing a background, so keeping both files would make every removal
        // cost more storage rather than less.
        val images = queueOf("a.jpg").withCutout("a.jpg", "cut.png").current!!.imagesToStore()

        assertEquals(listOf("cut.png"), images.imageUris)
        assertEquals(listOf("cut.png"), images.bgRemovedUris)
        assertEquals(listOf("a.jpg"), images.discardable)
    }

    @Test
    fun `a photo with no cut-out is stored as itself, with nothing to discard`() {
        val images = queueOf("a.jpg").current!!.imagesToStore()

        assertEquals(listOf("a.jpg"), images.imageUris)
        assertEquals(listOf(""), images.bgRemovedUris)
        assertTrue(images.discardable.isEmpty())
    }

    @Test
    fun `editing only touches the garment on screen`() {
        val state = queueOf("a.jpg", "b.jpg").withCategory("shoes").withBrand("Acme")

        assertEquals("shoes", state.drafts[0].category)
        assertEquals("Acme", state.drafts[0].brand)
        assertEquals(BulkAddState.DEFAULT_CATEGORY, state.drafts[1].category)
        assertEquals("", state.drafts[1].brand)
    }
}
