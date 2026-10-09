package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.SuggestedOutfit
import com.wardrobapp.presentation.OutfitsScreenState.Suggestion
import java.io.IOException
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A training session: what it asks for, what it writes, and what it counts. */
class TasteTrainingScreenModelTest {

    private fun suggestion(id: String, vararg garmentIds: String) = Suggestion(
        id = id,
        outfit = SuggestedOutfit(
            name = "Suggestion $id",
            score = 1.0,
            garments = garmentIds.map { testGarment(it) },
            reasons = emptyList(),
        ),
    )

    private fun round(from: Int, size: Int = TRAINING_ROUND_SIZE) =
        (from until from + size).map { n -> suggestion("s$n", "g${n}a", "g${n}b") }

    private inner class FakeSource : OutfitsSource {
        val requests = mutableListOf<SuggestionRequest>()
        var rounds = ArrayDeque(listOf(round(1), round(11)))
        var failWrites: Exception? = null
        var failSuggest: Exception? = null
        val writes = mutableListOf<String>()

        override suspend fun garment(id: String): GarmentRecord? = testGarment(id)
        override suspend fun suggest(request: SuggestionRequest): List<Suggestion> {
            failSuggest?.let { throw it }
            requests += request
            return rounds.removeFirstOrNull() ?: emptyList()
        }
        override suspend fun saved(includeArchived: Boolean) = SavedOutfits(emptyList(), 0)
        override suspend fun keep(suggestion: Suggestion) = log("keep ${suggestion.id}")
        override suspend fun rate(suggestion: Suggestion, rating: Int) = log("rate ${suggestion.id} $rating")
        override suspend fun unarchive(outfitId: String) = log("unarchive $outfitId")
        override suspend fun setPinned(outfitId: String, pinned: Boolean) = log("pin $outfitId $pinned")
        override suspend fun delete(outfitId: String) = log("delete $outfitId")

        private fun log(write: String) {
            failWrites?.let { throw it }
            writes += write
        }
    }

    @Test
    fun `a session opens by asking for a round of ten, exploring, with nothing seen yet`() = runTest {
        val source = FakeSource()
        val model = TasteTrainingScreenModel(this, source)
        advanceUntilIdle()

        val request = source.requests.single()
        assertEquals(TRAINING_ROUND_SIZE, request.count)
        assertTrue(request.explore)
        assertEquals(emptyList(), request.alreadySeen)
        assertNull(request.seedGarmentId)
        assertEquals("s1", model.state.value.current?.id)
        assertEquals(0, model.state.value.position)
    }

    @Test
    fun `rating writes the rating, counts it, and moves on`() = runTest {
        val source = FakeSource()
        val model = TasteTrainingScreenModel(this, source)
        advanceUntilIdle()

        model.onRated(5)
        advanceUntilIdle()
        model.onRated(1)
        advanceUntilIdle()
        model.onRated(3)
        advanceUntilIdle()

        assertEquals(listOf("rate s1 5", "rate s2 1", "rate s3 3"), source.writes)
        val state = model.state.value
        assertEquals("s4", state.current?.id)
        assertEquals(3, state.rated)
        assertEquals(1, state.liked)
        assertEquals(1, state.disliked)
        assertEquals(5, state.round[0].rating, "the stars stay on the card that was rated")
    }

    @Test
    fun `skipping moves on and writes nothing`() = runTest {
        val source = FakeSource()
        val model = TasteTrainingScreenModel(this, source)
        advanceUntilIdle()

        model.onSkipped()
        assertEquals("s2", model.state.value.current?.id)
        assertEquals(1, model.state.value.skipped)
        assertEquals(emptyList(), source.writes)
    }

    @Test
    fun `keeping an outfit saves it and stays on it`() = runTest {
        val source = FakeSource()
        val model = TasteTrainingScreenModel(this, source)
        advanceUntilIdle()

        model.onSaveRequested()
        advanceUntilIdle()

        assertEquals(listOf("keep s1"), source.writes)
        assertEquals("s1", model.state.value.current?.id)
        assertTrue(model.state.value.current!!.saved)
    }

    @Test
    fun `ten answers finish the round, and keep going asks past everything shown`() = runTest {
        val source = FakeSource()
        val model = TasteTrainingScreenModel(this, source)
        advanceUntilIdle()

        repeat(7) { model.onRated(4); advanceUntilIdle() }
        repeat(3) { model.onSkipped() }

        val finished = model.state.value
        assertTrue(finished.isRoundFinished)
        assertNull(finished.current)
        assertEquals(7, finished.rated)
        assertEquals(7, finished.liked)
        assertEquals(3, finished.skipped)
        assertFalse(finished.exhausted)

        model.onKeepGoing()
        advanceUntilIdle()

        val second = source.requests[1]
        assertEquals(10, second.alreadySeen.size, "every outfit of the first round is already seen")
        assertEquals(listOf("g1a", "g1b"), second.alreadySeen.first())
        assertEquals("s11", model.state.value.current?.id)
        assertEquals(7, model.state.value.rated, "the tallies are the session's, not the round's")
    }

    @Test
    fun `a rating that cannot be written leaves the card where it is, and says why`() = runTest {
        val source = FakeSource().apply { failWrites = IOException("locked") }
        val model = TasteTrainingScreenModel(this, source)
        advanceUntilIdle()

        model.onRated(4)
        advanceUntilIdle()

        val state = model.state.value
        assertEquals("s1", state.current?.id)
        assertNull(state.current?.rating, "the stars come back off a rating that was not recorded")
        assertEquals(0, state.rated)
        assertEquals("locked", state.error)
        assertFalse(state.writing)
    }

    @Test
    fun `a second round with nothing in it is the end of what the wardrobe can offer`() = runTest {
        val source = FakeSource().apply { rounds = ArrayDeque(listOf(round(1, size = 2))) }
        val model = TasteTrainingScreenModel(this, source)
        advanceUntilIdle()

        model.onSkipped()
        model.onSkipped()
        assertTrue(model.state.value.isRoundFinished)
        assertFalse(model.state.value.exhausted)

        model.onKeepGoing()
        advanceUntilIdle()

        assertTrue(model.state.value.exhausted)
        assertTrue(model.state.value.isRoundFinished)
        assertFalse(model.state.value.isEmpty, "spent is not the same as never having had anything")
        assertEquals(2, model.state.value.skipped, "the session's tallies survive the empty round")
    }

    @Test
    fun `a first round with nothing in it is a wardrobe nothing can be built from`() = runTest {
        val source = FakeSource().apply { rounds = ArrayDeque() }
        val model = TasteTrainingScreenModel(this, source)
        advanceUntilIdle()

        assertTrue(model.state.value.isEmpty)
        assertFalse(model.state.value.exhausted, "not spent; there was never anything")
    }

    @Test
    fun `a round that cannot be fetched is reported, and asked for again on retry`() = runTest {
        val source = FakeSource().apply { failSuggest = IOException("offline") }
        val model = TasteTrainingScreenModel(this, source)
        advanceUntilIdle()
        assertEquals("offline", model.state.value.error)

        source.failSuggest = null
        model.onRetry()
        advanceUntilIdle()
        assertNull(model.state.value.error)
        assertEquals("s1", model.state.value.current?.id)
    }
}
