package com.wardrobapp.presentation

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutfitDetailScreenModelTest {

    /** An outfit store in memory, recording what was asked of it. */
    private class FakeSource : OutfitDetailSource {
        var content: OutfitDetailContent? = OutfitDetailContent(
            outfit = testOutfit("o1", "g1", "g2"),
            garments = listOf(testGarment("g1"), testGarment("g2")),
            rating = null,
        )
        var failRating: Exception? = null
        var failDelete: Exception? = null
        var rateGate: CompletableDeferred<Unit>? = null
        val rated = mutableListOf<Pair<String, Int>>()
        val deleted = mutableListOf<String>()

        override suspend fun outfit(id: String) = content?.takeIf { it.outfit.id == id }

        override suspend fun rate(outfitId: String, rating: Int) {
            rateGate?.await()
            failRating?.let { throw it }
            rated += outfitId to rating
            content = content?.copy(rating = rating)
        }

        override suspend fun delete(outfitId: String) {
            failDelete?.let { throw it }
            deleted += outfitId
        }
    }

    @Test
    fun `loads the outfit, its garments and its rating`() = runTest {
        val source = FakeSource().apply { content = content!!.copy(rating = 4) }
        val model = OutfitDetailScreenModel(this, source, "o1")
        advanceUntilIdle()

        val state = model.state.value
        assertFalse(state.loading)
        assertEquals("o1", state.outfit?.id)
        assertEquals(listOf("g1", "g2"), state.garments.map { it.id })
        assertEquals(ratingSummary(listOf(4)), state.rating)
        assertFalse(state.missing)
    }

    @Test
    fun `an outfit that is not there is missing, not an error`() = runTest {
        val model = OutfitDetailScreenModel(this, FakeSource(), "nope")
        advanceUntilIdle()

        assertTrue(model.state.value.missing)
        assertNull(model.state.value.error)
    }

    @Test
    fun `rating saves it and shows the new rating`() = runTest {
        val source = FakeSource()
        val model = OutfitDetailScreenModel(this, source, "o1")
        advanceUntilIdle()

        model.onRated(5)
        advanceUntilIdle()

        assertEquals(listOf("o1" to 5), source.rated)
        assertEquals(ratingSummary(listOf(5)), model.state.value.rating)
        assertFalse(model.state.value.working)
    }

    @Test
    fun `a second tap while the first rating is saving is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = FakeSource().apply { rateGate = gate }
        val model = OutfitDetailScreenModel(this, source, "o1")
        advanceUntilIdle()

        model.onRated(3)
        advanceUntilIdle()
        model.onRated(5)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("o1" to 3), source.rated)
    }

    @Test
    fun `a rating that fails says so in the reader's language`() = runTest {
        val source = FakeSource().apply { failRating = IOException() }
        val model = OutfitDetailScreenModel(this, source, "o1")
        advanceUntilIdle()

        model.onRated(2)
        advanceUntilIdle()

        assertFalse(model.state.value.working)
        assertEquals(ErrorFallback.RATING_NOT_SAVED, model.state.value.errorFallback)
    }

    @Test
    fun `deleting asks first, and deletes only once confirmed`() = runTest {
        val source = FakeSource()
        val model = OutfitDetailScreenModel(this, source, "o1")
        advanceUntilIdle()

        model.onDeleteRequested()
        assertTrue(model.state.value.confirmingDelete)
        model.onDeleteDismissed()
        advanceUntilIdle()
        assertEquals(emptyList(), source.deleted)

        model.onDeleteRequested()
        model.onDeleteConfirmed()
        advanceUntilIdle()
        assertEquals(listOf("o1"), source.deleted)
        assertTrue(model.state.value.deleted)
    }

    @Test
    fun `a delete that fails leaves the outfit and says why`() = runTest {
        val source = FakeSource().apply { failDelete = IOException("locked") }
        val model = OutfitDetailScreenModel(this, source, "o1")
        advanceUntilIdle()

        model.onDeleteConfirmed()
        advanceUntilIdle()

        assertFalse(model.state.value.deleted)
        assertEquals("locked", model.state.value.error)
        assertEquals(ErrorFallback.OUTFIT_NOT_DELETED, model.state.value.errorFallback)
    }

    @Test
    fun `a load failure is reported`() {
        val scope = TestScope()
        val failing = object : OutfitDetailSource by FakeSource() {
            override suspend fun outfit(id: String): OutfitDetailContent? = throw IOException("gone")
        }
        val model = OutfitDetailScreenModel(scope, failing, "o1")
        scope.advanceUntilIdle()

        assertEquals("gone", model.state.value.error)
        assertFalse(model.state.value.loading)
    }
}
