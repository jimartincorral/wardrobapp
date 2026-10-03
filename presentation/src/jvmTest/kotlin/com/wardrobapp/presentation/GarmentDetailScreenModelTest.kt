package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.presentation.GarmentDetailScreenState.Confirm
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GarmentDetailScreenModelTest {

    private class FakeSource : GarmentDetailSource {
        var record: GarmentRecord? = testGarment("g1").copy(
            imageUri = "front.jpg",
            imageUris = listOf("front.jpg", "back.jpg"),
        )
        var cutGate: CompletableDeferred<Unit>? = null
        var failCut: Exception? = null
        val writes = mutableListOf<String>()
        val saved = mutableListOf<Pair<BackgroundEdit, Boolean>>()

        override suspend fun garment(id: String) = record?.takeIf { it.id == id }
        override suspend fun setInUse(id: String, inUse: Boolean) {
            writes += "inUse $id $inUse"
            record = record?.copy(isAvailable = inUse)
        }
        override suspend fun delete(id: String) {
            writes += "delete $id"
        }
        override suspend fun cutOut(photo: String): String {
            cutGate?.await()
            failCut?.let { throw it }
            return photo.removeSuffix(".jpg") + "_nobg.png"
        }
        override suspend fun savePhotos(id: String, edit: BackgroundEdit, alsoImages: Boolean) {
            saved += edit to alsoImages
        }
    }

    @Test
    fun `loads the garment, and an absent one is missing`() = runTest {
        val model = GarmentDetailScreenModel(this, FakeSource(), "g1")
        val gone = GarmentDetailScreenModel(this, FakeSource(), "nope")
        advanceUntilIdle()

        assertFalse(model.state.value.loading)
        assertEquals(garmentDetail(FakeSource().record!!, 0), model.state.value.view)
        assertTrue(gone.state.value.missing)
    }

    @Test
    fun `selecting a photo recomputes the view without re-reading`() = runTest {
        val source = FakeSource()
        val model = GarmentDetailScreenModel(this, source, "g1")
        advanceUntilIdle()
        source.record = null

        model.onPhotoSelected(1)

        assertEquals(garmentDetail(testGarment("g1").copy(imageUri = "front.jpg", imageUris = listOf("front.jpg", "back.jpg")), 1), model.state.value.view)
    }

    @Test
    fun `retiring asks first, then writes and re-reads`() = runTest {
        val source = FakeSource()
        val model = GarmentDetailScreenModel(this, source, "g1")
        advanceUntilIdle()

        model.onRetireRequested()
        assertEquals(Confirm.RETIRE, model.state.value.confirming)
        model.onConfirmed()
        advanceUntilIdle()

        assertEquals(listOf("inUse g1 false"), source.writes)
        assertNull(model.state.value.confirming)
        assertFalse(model.state.value.working)
    }

    @Test
    fun `returning to the wardrobe needs no confirmation`() = runTest {
        val source = FakeSource()
        val model = GarmentDetailScreenModel(this, source, "g1")
        advanceUntilIdle()

        model.onReturnedToWardrobe()
        advanceUntilIdle()

        assertEquals(listOf("inUse g1 true"), source.writes)
    }

    @Test
    fun `deleting reports the garment gone`() = runTest {
        val source = FakeSource()
        val model = GarmentDetailScreenModel(this, source, "g1")
        advanceUntilIdle()

        model.onDeleteRequested()
        model.onConfirmed()
        advanceUntilIdle()

        assertEquals(listOf("delete g1"), source.writes)
        assertTrue(model.state.value.deleted)
    }

    @Test
    fun `removing a background stores the cut-out in the selected photo's slot`() = runTest {
        val source = FakeSource()
        val model = GarmentDetailScreenModel(this, source, "g1")
        advanceUntilIdle()

        model.onPhotoSelected(1)
        model.onRemoveBackground()
        advanceUntilIdle()

        val (edit, alsoImages) = source.saved.single()
        assertTrue(alsoImages)
        assertEquals(
            withBackgroundRemovedAt(listOf("front.jpg", "back.jpg"), emptyList(), index = 1, cutout = "back_nobg.png"),
            edit,
        )
    }

    @Test
    fun `switching photos while a background is removed does not move the cut-out`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = FakeSource().apply { cutGate = gate }
        val model = GarmentDetailScreenModel(this, source, "g1")
        advanceUntilIdle()

        model.onRemoveBackground()
        advanceUntilIdle()
        model.onPhotoSelected(1)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            withBackgroundRemovedAt(listOf("front.jpg", "back.jpg"), emptyList(), index = 0, cutout = "front_nobg.png"),
            source.saved.single().first,
        )
    }

    @Test
    fun `a background that cannot be removed says so in the reader's language`() = runTest {
        val source = FakeSource().apply { failCut = IOException() }
        val model = GarmentDetailScreenModel(this, source, "g1")
        advanceUntilIdle()

        model.onRemoveBackground()
        advanceUntilIdle()

        assertFalse(model.state.value.working)
        assertEquals(ErrorFallback.BACKGROUND_NOT_REMOVED, model.state.value.actionErrorFallback)
        assertEquals(emptyList(), source.saved)

        model.onActionErrorDismissed()
        assertNull(model.state.value.actionErrorFallback)
    }
}
