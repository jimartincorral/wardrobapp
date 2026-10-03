package com.wardrobapp.presentation

import java.io.IOException
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Picked photos are their own names here: the model only hands them back. */
class BulkAddScreenModelTest {

    private class FakePhotos : PhotoWork<String> {
        val unreadable = mutableSetOf<String>()
        var failCut = false
        val stored = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        override suspend fun store(photo: String): String {
            if (photo in unreadable) throw IOException("unreadable")
            return "stored/$photo".also { stored += it }
        }
        override suspend fun delete(photo: String) {
            deleted += photo
        }
        override suspend fun colors(photo: String) = listOf("#112233")
        override suspend fun cutOut(photo: String): String {
            if (failCut) throw IOException()
            return "$photo.nobg"
        }
    }

    private class FakeSource : BulkAddSource {
        var fail: Exception? = null
        val saved = mutableListOf<BulkAddState.Draft>()
        override suspend fun save(draft: BulkAddState.Draft) {
            fail?.let { throw it }
            saved += draft
        }
    }

    @Test
    fun `picked photos are stored and queued in order, with their colours read`() = runTest {
        val model = BulkAddScreenModel(this, FakePhotos(), FakeSource())

        model.onPhotosPicked(listOf("a.jpg", "b.jpg"))
        advanceUntilIdle()

        val queue = model.state.value.queue
        assertEquals(listOf("stored/a.jpg", "stored/b.jpg"), queue.drafts.map { it.imageUri })
        assertEquals(listOf("#112233"), queue.current?.colorPalette)
        assertFalse(model.state.value.importing)
        assertNull(model.state.value.errorFallback)
    }

    @Test
    fun `one photo that cannot be stored does not cost the others`() = runTest {
        val photos = FakePhotos().apply { unreadable += "b.jpg" }
        val model = BulkAddScreenModel(this, photos, FakeSource())

        model.onPhotosPicked(listOf("a.jpg", "b.jpg", "c.jpg"))
        advanceUntilIdle()

        assertEquals(listOf("stored/a.jpg", "stored/c.jpg"), model.state.value.queue.drafts.map { it.imageUri })
        assertEquals(ErrorFallback.PHOTO_NOT_IMPORTED, model.state.value.errorFallback)
    }

    @Test
    fun `saving moves on to the next garment`() = runTest {
        val source = FakeSource()
        val model = BulkAddScreenModel(this, FakePhotos(), source)
        model.onPhotosPicked(listOf("a.jpg", "b.jpg"))
        advanceUntilIdle()

        model.onCategorySelected("bottoms")
        model.onSaveRequested()
        advanceUntilIdle()

        assertEquals("bottoms", source.saved.single().category)
        assertEquals("stored/b.jpg", model.state.value.queue.current?.imageUri)
    }

    @Test
    fun `a failed save keeps the garment at the head of the queue`() = runTest {
        val source = FakeSource().apply { fail = IOException("full") }
        val model = BulkAddScreenModel(this, FakePhotos(), source)
        model.onPhotosPicked(listOf("a.jpg"))
        advanceUntilIdle()

        model.onSaveRequested()
        advanceUntilIdle()

        assertEquals("stored/a.jpg", model.state.value.queue.current?.imageUri)
        assertEquals(ErrorFallback.GARMENT_NOT_SAVED, model.state.value.errorFallback)
    }

    @Test
    fun `skipping throws the garment's photo away`() = runTest {
        val photos = FakePhotos()
        val model = BulkAddScreenModel(this, photos, FakeSource())
        model.onPhotosPicked(listOf("a.jpg", "b.jpg"))
        advanceUntilIdle()

        model.onSkipRequested()
        advanceUntilIdle()

        assertEquals(listOf("stored/a.jpg"), photos.deleted)
        assertEquals("stored/b.jpg", model.state.value.queue.current?.imageUri)
    }

    @Test
    fun `a crop replaces the photo, and the old one goes only once the new one is stored`() = runTest {
        val photos = FakePhotos()
        val model = BulkAddScreenModel(this, photos, FakeSource())
        model.onPhotosPicked(listOf("a.jpg"))
        advanceUntilIdle()

        photos.unreadable += "crop.jpg"
        model.onPhotoCropped("crop.jpg")
        advanceUntilIdle()
        assertEquals(emptyList(), photos.deleted, "a crop that failed to store must not delete the photo it replaces")
        assertEquals(ErrorFallback.PHOTO_NOT_IMPORTED, model.state.value.errorFallback)

        photos.unreadable.clear()
        model.onPhotoCropped("crop.jpg")
        advanceUntilIdle()
        assertEquals(listOf("stored/a.jpg"), photos.deleted)
        assertEquals("stored/crop.jpg", model.state.value.queue.current?.imageUri)
    }

    @Test
    fun `a background is removed, and undoing it deletes the cut-out`() = runTest {
        val photos = FakePhotos()
        val model = BulkAddScreenModel(this, photos, FakeSource())
        model.onPhotosPicked(listOf("a.jpg"))
        advanceUntilIdle()

        model.onRemoveBackground()
        advanceUntilIdle()
        assertEquals("stored/a.jpg.nobg", model.state.value.queue.current?.cutoutUri)

        model.onUndoBackground()
        advanceUntilIdle()
        assertEquals("", model.state.value.queue.current?.cutoutUri)
        assertEquals(listOf("stored/a.jpg.nobg"), photos.deleted)
    }

    @Test
    fun `a background that cannot be removed says so`() = runTest {
        val photos = FakePhotos().apply { failCut = true }
        val model = BulkAddScreenModel(this, photos, FakeSource())
        model.onPhotosPicked(listOf("a.jpg"))
        advanceUntilIdle()

        model.onRemoveBackground()
        advanceUntilIdle()

        assertFalse(model.state.value.removingBackground)
        assertEquals(ErrorFallback.BACKGROUND_NOT_REMOVED, model.state.value.errorFallback)
    }
}
