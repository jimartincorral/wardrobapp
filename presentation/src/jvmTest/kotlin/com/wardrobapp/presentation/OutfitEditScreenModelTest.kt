package com.wardrobapp.presentation

import com.wardrobapp.data.OutfitRecord
import com.wardrobapp.domain.Occasion
import java.io.IOException
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutfitEditScreenModelTest {

    private class FakeSource : OutfitEditSource {
        var garments = listOf(testGarment("g1", "tops", "Shirt"), testGarment("g2", "bottoms", "Jeans"))
        var outfits = mutableMapOf("o1" to testOutfit("o1", "g2"))
        var failWrite: Exception? = null
        val created = mutableListOf<OutfitDraft>()
        val updated = mutableListOf<Pair<String, OutfitDraft>>()

        override suspend fun wardrobe() = garments
        override suspend fun outfit(id: String): OutfitRecord? = outfits[id]
        override suspend fun create(draft: OutfitDraft) {
            failWrite?.let { throw it }
            created += draft
        }
        override suspend fun update(id: String, draft: OutfitDraft) {
            failWrite?.let { throw it }
            updated += id to draft
        }
    }

    @Test
    fun `a new outfit starts empty with the wardrobe to pick from`() = runTest {
        val model = OutfitEditScreenModel(this, FakeSource(), outfitId = null)
        advanceUntilIdle()

        assertFalse(model.isEditing)
        assertFalse(model.state.value.loading)
        assertEquals(listOf("g1", "g2"), model.state.value.garments.map { it.id })
        assertEquals(emptyList(), model.state.value.edit.garmentIds)
    }

    @Test
    fun `editing starts from the outfit as it is`() = runTest {
        val model = OutfitEditScreenModel(this, FakeSource(), outfitId = "o1")
        advanceUntilIdle()

        assertTrue(model.isEditing)
        assertEquals(listOf("g2"), model.state.value.edit.garmentIds)
        assertEquals("Outfit o1", model.state.value.edit.name)
    }

    @Test
    fun `editing an outfit that is not there says it is missing`() = runTest {
        val model = OutfitEditScreenModel(this, FakeSource(), outfitId = "gone")
        advanceUntilIdle()

        assertTrue(model.state.value.missing)
    }

    @Test
    fun `an empty outfit is not saved`() = runTest {
        val source = FakeSource()
        val model = OutfitEditScreenModel(this, source, outfitId = null)
        advanceUntilIdle()

        model.onSaveRequested()
        advanceUntilIdle()

        assertEquals(emptyList(), source.created)
        assertFalse(model.state.value.saved)
    }

    @Test
    fun `a new outfit is created with the name the editor decides`() = runTest {
        val source = FakeSource()
        val model = OutfitEditScreenModel(this, source, outfitId = null)
        advanceUntilIdle()

        model.onGarmentToggled("g1")
        model.onOccasionTapped(Occasion.WORK)
        model.onSaveRequested()
        advanceUntilIdle()

        val draft = source.created.single()
        assertEquals(listOf("g1"), draft.garmentIds)
        assertEquals(Occasion.WORK, draft.occasion)
        assertEquals(model.state.value.edit.nameFor(source.garments), draft.name)
        assertTrue(model.state.value.saved)
    }

    @Test
    fun `an edited outfit is updated in place rather than created again`() = runTest {
        val source = FakeSource()
        val model = OutfitEditScreenModel(this, source, outfitId = "o1")
        advanceUntilIdle()

        model.onNameChanged("Fridays")
        model.onSaveRequested()
        advanceUntilIdle()

        assertEquals(emptyList(), source.created)
        assertEquals("o1" to "Fridays", source.updated.single().let { it.first to it.second.name })
    }

    @Test
    fun `a save that fails says so, and the error can be dismissed`() = runTest {
        val source = FakeSource().apply { failWrite = IOException("full") }
        val model = OutfitEditScreenModel(this, source, outfitId = "o1")
        advanceUntilIdle()

        model.onSaveRequested()
        advanceUntilIdle()
        assertFalse(model.state.value.saving)
        assertEquals("full", model.state.value.error)
        assertEquals(ErrorFallback.OUTFIT_NOT_SAVED, model.state.value.errorFallback)

        model.onErrorDismissed()
        assertEquals(null, model.state.value.errorFallback)
    }

    @Test
    fun `a wardrobe that cannot be read is reported as that`() = runTest {
        val failing = object : OutfitEditSource by FakeSource() {
            override suspend fun wardrobe(): Nothing = throw IOException()
        }
        val model = OutfitEditScreenModel(this, failing, outfitId = null)
        advanceUntilIdle()

        assertFalse(model.state.value.loading)
        assertEquals(ErrorFallback.WARDROBE_UNREADABLE, model.state.value.errorFallback)
    }
}
