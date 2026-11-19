package com.wardrobapp.presentation

import com.wardrobapp.data.DuplicateGarment
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.domain.DuplicateCandidate
import com.wardrobapp.domain.GarmentReading
import com.wardrobapp.domain.ImportedGarmentPreview
import com.wardrobapp.domain.ReadType
import com.wardrobapp.domain.Season
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The queue around the garment form. Picked photos are their own names here:
 * the model only hands them back.
 *
 * Driven through the real GarmentFormScreenModel each garment opens, rather
 * than a stand-in for it, since "bulk add is the ordinary form" is the claim
 * under test and a stand-in would be a second form again.
 */
class BulkAddScreenModelTest {

    private class FakePhotos : PhotoWork<String> {
        val unreadable = mutableSetOf<String>()
        val deleted = mutableListOf<String>()
        val read = mutableListOf<String>()

        override suspend fun store(photo: String): String {
            if (photo in unreadable) throw IOException("unreadable")
            return "stored/$photo"
        }
        override suspend fun delete(photo: String) {
            deleted += photo
        }
        override suspend fun colors(photo: String) = listOf("#112233")
        override suspend fun cutOut(photo: String) = "$photo.nobg"
        override suspend fun read(photo: String): GarmentReading? {
            read += photo
            return if (photo.endsWith("boots.jpg")) GarmentReading(type = ReadType("shoes", "Boots")) else null
        }
    }

    private class FakeSource : GarmentFormSource {
        var fail: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        val saved = mutableListOf<GarmentFormState>()

        override suspend fun garment(id: String): GarmentRecord? = null
        override suspend fun brands() = listOf("Arket")
        override suspend fun duplicatesOf(candidate: DuplicateCandidate) = emptyList<DuplicateGarment>()
        override suspend fun save(garmentId: String?, form: GarmentFormState, previouslyStored: List<String>) {
            gate?.await()
            fail?.let { throw it }
            saved += form
        }
    }

    /** Never reached: a queued garment arrives with its photo, so the form offers no import. */
    private object NoImports : GarmentImporter {
        override fun check(url: String) = error("bulk add does not import")
        override suspend fun import(url: String): ImportedGarmentPreview = error("bulk add does not import")
    }

    private fun kotlinx.coroutines.CoroutineScope.model(
        photos: FakePhotos = FakePhotos(),
        source: FakeSource = FakeSource(),
    ) = BulkAddScreenModel(this, photos, source, NoImports)

    private val BulkAddScreenModel<String>.garment: GarmentFormScreenState
        get() = assertNotNull(form.value, "a garment should be on screen").state.value

    @Test
    fun `picked photos are queued in order, and the first opens the form on its own photo`() = runTest {
        val model = model()

        model.onPhotosPicked(listOf("a.jpg", "b.jpg"))
        advanceUntilIdle()

        assertEquals(listOf("stored/a.jpg"), model.garment.form.imageUris)
        assertEquals(listOf("#112233"), model.garment.form.colorPalette)
        assertEquals(listOf("stored/b.jpg"), model.state.value.queue.waiting)
        assertEquals(1, model.state.value.queue.position)
        assertEquals(2, model.state.value.queue.total)
        assertFalse(model.state.value.importing)
        assertNull(model.state.value.errorFallback)
    }

    @Test
    fun `one photo that cannot be stored does not cost the others`() = runTest {
        val photos = FakePhotos().apply { unreadable += "b.jpg" }
        val model = model(photos)

        model.onPhotosPicked(listOf("a.jpg", "b.jpg", "c.jpg"))
        advanceUntilIdle()

        assertEquals(listOf("stored/c.jpg"), model.state.value.queue.waiting)
        assertEquals(2, model.state.value.queue.total)
        assertEquals(ErrorFallback.PHOTO_NOT_IMPORTED, model.state.value.errorFallback)
    }

    @Test
    fun `each garment is the whole form, and is saved with everything on it`() = runTest {
        // What this screen was rebuilt for: it used to ask for a category, a type
        // and a brand, and nothing else reached the garment.
        val source = FakeSource()
        val model = model(source = source)
        model.onPhotosPicked(listOf("a.jpg"))
        advanceUntilIdle()

        val form = model.form.value!!
        form.onCategorySelected("bottoms")
        form.onSeasonToggled(Season.entries.first())
        form.onMaterialToggled("denim")
        form.onSizeChanged("M")
        form.onTagsChanged(listOf("work"))
        form.onSaveRequested()
        advanceUntilIdle()

        val saved = source.saved.single()
        assertEquals("bottoms", saved.category)
        assertEquals(listOf(Season.entries.first()), saved.seasons)
        assertEquals(listOf("denim"), saved.materials)
        assertEquals("M", saved.size)
        assertEquals(listOf("work"), saved.tags)
        assertEquals(listOf("stored/a.jpg"), saved.imageUris)
    }

    @Test
    fun `saving opens a fresh form on the next photo`() = runTest {
        val model = model()
        model.onPhotosPicked(listOf("a.jpg", "b.jpg"))
        advanceUntilIdle()
        val first = model.form.value!!

        first.onBrandChanged("Arket")
        first.onSizeChanged("M")
        first.onSaveRequested()
        advanceUntilIdle()

        // A new form rather than the old one emptied: nothing typed for one
        // garment may land on the next.
        assertNotSame(first, model.form.value)
        assertEquals(listOf("stored/b.jpg"), model.garment.form.imageUris)
        assertEquals("", model.garment.form.brand)
        assertEquals("", model.garment.form.size)
        assertEquals(1, model.state.value.queue.added)
        assertEquals(2, model.state.value.queue.position)
    }

    @Test
    fun `a failed save keeps the garment on screen`() = runTest {
        val source = FakeSource().apply { fail = IOException("full") }
        val model = model(source = source)
        model.onPhotosPicked(listOf("a.jpg", "b.jpg"))
        advanceUntilIdle()
        val first = model.form.value!!

        first.onSaveRequested()
        advanceUntilIdle()

        assertSame(first, model.form.value)
        assertEquals("full", model.garment.error)
        assertEquals(0, model.state.value.queue.added)
    }

    @Test
    fun `skipping throws away every photo the garment had, and moves on`() = runTest {
        val photos = FakePhotos()
        val model = model(photos)
        model.onPhotosPicked(listOf("a.jpg", "b.jpg"))
        advanceUntilIdle()

        model.form.value!!.onRemoveBackground()
        advanceUntilIdle()
        model.onSkipRequested()
        advanceUntilIdle()

        assertEquals(setOf("stored/a.jpg", "stored/a.jpg.nobg"), photos.deleted.toSet())
        assertEquals(listOf("stored/b.jpg"), model.garment.form.imageUris)
        assertEquals(1, model.state.value.queue.skipped)
    }

    @Test
    fun `a garment cannot be skipped while it is being saved`() = runTest {
        // Otherwise the save lands with a row pointing at photos the skip has
        // just deleted.
        val photos = FakePhotos()
        val source = FakeSource().apply { gate = CompletableDeferred() }
        val model = model(photos, source)
        model.onPhotosPicked(listOf("a.jpg", "b.jpg"))
        advanceUntilIdle()

        model.form.value!!.onSaveRequested()
        advanceUntilIdle()
        model.onSkipRequested()
        advanceUntilIdle()
        assertEquals(0, model.state.value.queue.skipped)

        source.gate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(emptyList(), photos.deleted)
        assertEquals(1, model.state.value.queue.added)
        assertEquals(listOf("stored/b.jpg"), model.garment.form.imageUris)
    }

    @Test
    fun `the last garment saved finishes the queue`() = runTest {
        val model = model()
        model.onPhotosPicked(listOf("a.jpg", "b.jpg"))
        advanceUntilIdle()

        model.onSkipRequested()
        advanceUntilIdle()
        model.form.value!!.onSaveRequested()
        advanceUntilIdle()

        assertNull(model.form.value)
        assertTrue(model.state.value.queue.isFinished)
        assertEquals(1, model.state.value.queue.added)
        assertEquals(1, model.state.value.queue.skipped)
    }

    @Test
    fun `a finished batch can take on more photos`() = runTest {
        val model = model()
        model.onPhotosPicked(listOf("a.jpg"))
        advanceUntilIdle()
        model.form.value!!.onSaveRequested()
        advanceUntilIdle()

        model.onPhotosPicked(listOf("b.jpg"))
        advanceUntilIdle()

        assertEquals(listOf("stored/b.jpg"), model.garment.form.imageUris)
        assertEquals(2, model.state.value.queue.position)
        assertEquals(2, model.state.value.queue.total)
    }

    @Test
    fun `each queued photo is read as it arrives, and only once`() = runTest {
        // Read ahead, so the second garment opens with its type already known
        // -- and the form asking again for the same photo is handed that
        // reading rather than starting another.
        val photos = FakePhotos()
        val model = model(photos)
        model.onPhotosPicked(listOf("shirt.jpg", "boots.jpg"))
        advanceUntilIdle()
        assertTrue("stored/boots.jpg" in photos.read, "the waiting photo should already have been read")

        model.onSkipRequested()
        advanceUntilIdle()

        assertEquals("shoes", model.garment.form.category)
        assertEquals(listOf("Boots"), model.garment.form.subcategories)
        assertEquals(1, photos.read.count { it == "stored/boots.jpg" })
    }
}
