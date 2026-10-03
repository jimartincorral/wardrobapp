package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.SuggestedOutfit
import com.wardrobapp.domain.Occasion
import com.wardrobapp.presentation.OutfitsScreenState.Suggestion
import java.io.IOException
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutfitsScreenModelTest {

    private fun suggestion(id: String, vararg garmentIds: String) = Suggestion(
        id = id,
        outfit = SuggestedOutfit(
            name = "Suggestion $id",
            score = 1.0,
            garments = garmentIds.map { testGarment(it) },
            reasons = emptyList(),
        ),
    )

    private inner class FakeSource : OutfitsSource {
        val requests = mutableListOf<SuggestionRequest>()
        var next = listOf(suggestion("s1", "g1", "g2"), suggestion("s2", "g3", "g4"))
        var failWrites: Exception? = null
        val writes = mutableListOf<String>()
        var archived = 0L

        override suspend fun garment(id: String): GarmentRecord? = testGarment(id)
        override suspend fun suggest(request: SuggestionRequest): List<Suggestion> {
            requests += request
            return next
        }
        override suspend fun saved(includeArchived: Boolean) =
            SavedOutfits(outfits = listOf(testOutfit("o1", "g1")), archivedCount = archived)
        override suspend fun keep(suggestion: Suggestion) = log("keep ${suggestion.id}")
        override suspend fun rate(suggestion: Suggestion, rating: Int) {
            log("rate ${suggestion.id} $rating")
            archived++
        }
        override suspend fun unarchive(outfitId: String) = log("unarchive $outfitId")
        override suspend fun setPinned(outfitId: String, pinned: Boolean) = log("pin $outfitId $pinned")
        override suspend fun delete(outfitId: String) = log("delete $outfitId")

        private fun log(write: String) {
            failWrites?.let { throw it }
            writes += write
        }
    }

    @Test
    fun `loads the saved outfits, and suggests nothing until asked`() = runTest {
        val source = FakeSource()
        val model = OutfitsScreenModel(this, source)
        advanceUntilIdle()

        assertEquals(listOf("o1"), model.state.value.saved.map { it.id })
        assertEquals(emptyList(), source.requests)
    }

    @Test
    fun `a second batch is asked not to repeat what is on screen`() = runTest {
        val source = FakeSource()
        val model = OutfitsScreenModel(this, source)
        advanceUntilIdle()

        model.onOccasionTapped(Occasion.WORK)
        model.generate()
        advanceUntilIdle()
        model.generate()
        advanceUntilIdle()

        assertEquals(emptyList(), source.requests.first().alreadySeen)
        assertEquals(listOf(listOf("g1", "g2"), listOf("g3", "g4")), source.requests.last().alreadySeen)
        assertEquals(Occasion.WORK, source.requests.last().filters.occasion)
        assertTrue(model.state.value.hasGenerated)
    }

    @Test
    fun `suggestions built around a garment ask for it, until cleared`() = runTest {
        val source = FakeSource()
        val model = OutfitsScreenModel(this, source)
        advanceUntilIdle()

        model.onSeedRequested("g7")
        advanceUntilIdle()
        assertEquals("g7", source.requests.last().seedGarmentId)
        assertEquals("g7", model.state.value.seed?.id)

        model.onSeedCleared()
        advanceUntilIdle()
        assertNull(source.requests.last().seedGarmentId)
    }

    @Test
    fun `saving a suggestion marks it saved once it is`() = runTest {
        val source = FakeSource()
        val model = OutfitsScreenModel(this, source)
        model.generate()
        advanceUntilIdle()

        model.onSaveRequested(model.state.value.suggestions.first())
        advanceUntilIdle()

        assertEquals(listOf("keep s1"), source.writes)
        assertTrue(model.state.value.suggestions.first().saved)
    }

    @Test
    fun `a save that fails is not shown as saved`() = runTest {
        val source = FakeSource()
        val model = OutfitsScreenModel(this, source)
        model.generate()
        advanceUntilIdle()
        source.failWrites = IOException("full")

        model.onSaveRequested(model.state.value.suggestions.first())
        advanceUntilIdle()

        assertFalse(model.state.value.suggestions.first().saved)
    }

    @Test
    fun `rating stores the rating, then offers to keep the outfit`() = runTest {
        val source = FakeSource()
        val model = OutfitsScreenModel(this, source)
        model.generate()
        advanceUntilIdle()
        val first = model.state.value.suggestions.first()

        model.onRated(first, 2)
        assertEquals(2, model.state.value.suggestions.first().rating, "the stars show before the write lands")
        advanceUntilIdle()

        assertEquals(listOf("rate s1 2"), source.writes)
        assertEquals("s1", model.state.value.keeping?.id)
        assertEquals(1, model.state.value.archivedCount)

        model.onKeepRequested()
        advanceUntilIdle()
        assertEquals(listOf("rate s1 2", "unarchive s1"), source.writes)
        assertTrue(model.state.value.suggestions.first().saved)
        assertNull(model.state.value.keeping)
    }

    @Test
    fun `a rating that fails does not offer to keep anything`() = runTest {
        val source = FakeSource()
        val model = OutfitsScreenModel(this, source)
        model.generate()
        advanceUntilIdle()
        source.failWrites = IOException()

        model.onRated(model.state.value.suggestions.first(), 4)
        advanceUntilIdle()

        assertNull(model.state.value.keeping)
    }

    @Test
    fun `deleting asks first`() = runTest {
        val source = FakeSource()
        val model = OutfitsScreenModel(this, source)
        advanceUntilIdle()
        val outfit = model.state.value.saved.first()

        model.onDeleteRequested(outfit)
        model.onDeleteDismissed()
        model.onDeleteConfirmed()
        advanceUntilIdle()
        assertEquals(emptyList(), source.writes)

        model.onDeleteRequested(outfit)
        model.onDeleteConfirmed()
        advanceUntilIdle()
        assertEquals(listOf("delete o1"), source.writes)
    }

    @Test
    fun `pinning flips the outfit's pin`() = runTest {
        val source = FakeSource()
        val model = OutfitsScreenModel(this, source)
        advanceUntilIdle()

        model.onPinToggled(model.state.value.saved.first())
        advanceUntilIdle()

        assertEquals(listOf("pin o1 true"), source.writes)
    }
}
