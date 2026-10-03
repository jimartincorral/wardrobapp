package com.wardrobapp.presentation

import com.wardrobapp.data.DuplicateGarmentGroup
import com.wardrobapp.data.GapWithPhotos
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StatisticsScreenModelTest {

    private class FakeSource : StatisticsSource {
        var countReads = 0
        var sweeps = 0
        var gapSearches = 0
        var failSweep = false
        var sweepGate: CompletableDeferred<Unit>? = null

        override suspend fun counts(): StatisticsCounts {
            countReads++
            return StatisticsCounts(
                inUse = 3,
                retired = 1,
                categories = listOf(Distribution("tops", 2), Distribution("bottoms", 1)),
                colors = emptyList(),
                brands = listOf(Distribution("Zara", 1), Distribution("Acne", 2)),
                subcategories = emptyMap(),
                lifespans = emptyList(),
            )
        }

        override suspend fun duplicates(): List<DuplicateGarmentGroup> {
            sweeps++
            sweepGate?.await()
            if (failSweep) throw IOException()
            return emptyList()
        }

        override suspend fun gaps(): List<GapWithPhotos> {
            gapSearches++
            return emptyList()
        }
    }

    @Test
    fun `reads the counts but not the expensive sections`() = runTest {
        val source = FakeSource()
        val model = StatisticsScreenModel(this, source)
        advanceUntilIdle()

        assertFalse(model.state.value.loading)
        assertEquals(1, source.countReads)
        assertEquals(0, source.sweeps, "the sweep waits until its section is opened")
        assertEquals(0, source.gapSearches)
    }

    @Test
    fun `opening a section looks for its answer, once`() = runTest {
        val source = FakeSource()
        val model = StatisticsScreenModel(this, source)
        advanceUntilIdle()

        model.onSectionTapped(StatisticsSection.DUPLICATES)
        advanceUntilIdle()
        assertNotNull(model.state.value.duplicates)

        model.onSectionTapped(StatisticsSection.DUPLICATES)
        model.onSectionTapped(StatisticsSection.DUPLICATES)
        advanceUntilIdle()
        assertEquals(1, source.sweeps, "closing and reopening keeps the answer it has")
    }

    @Test
    fun `reopening while a sweep is running does not start a second`() = runTest {
        val source = FakeSource().apply { sweepGate = CompletableDeferred() }
        val model = StatisticsScreenModel(this, source)
        advanceUntilIdle()

        model.onSectionTapped(StatisticsSection.DUPLICATES)
        advanceUntilIdle()
        model.onSectionTapped(StatisticsSection.DUPLICATES)
        model.onSectionTapped(StatisticsSection.DUPLICATES)
        advanceUntilIdle()
        source.sweepGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, source.sweeps)
    }

    @Test
    fun `a refresh re-runs the open sections, since their answers went stale`() = runTest {
        val source = FakeSource()
        val model = StatisticsScreenModel(this, source)
        advanceUntilIdle()
        model.onSectionTapped(StatisticsSection.DUPLICATES)
        model.onSectionTapped(StatisticsSection.GAPS)
        advanceUntilIdle()

        model.refresh()
        advanceUntilIdle()

        assertEquals(2, source.sweeps)
        assertEquals(2, source.gapSearches)
        assertNotNull(model.state.value.duplicates, "an open section must not be left spinning")
    }

    @Test
    fun `a failed sweep empties its section without failing the page`() = runTest {
        val source = FakeSource().apply { failSweep = true }
        val model = StatisticsScreenModel(this, source)
        advanceUntilIdle()

        model.onSectionTapped(StatisticsSection.DUPLICATES)
        advanceUntilIdle()

        assertNull(model.state.value.duplicates)
        assertNull(model.state.value.error)
    }

    @Test
    fun `re-sorting brands uses the counts already read`() = runTest {
        val source = FakeSource()
        val model = StatisticsScreenModel(this, source)
        advanceUntilIdle()

        model.onBrandSortChanged(BrandSort.ALPHA)

        assertEquals(1, source.countReads)
        assertEquals(BrandSort.ALPHA, model.state.value.brandSort)
        assertEquals(
            statisticsView(
                inUse = 3,
                categories = listOf(Distribution("tops", 2), Distribution("bottoms", 1)),
                colors = emptyList(),
                brands = listOf(Distribution("Zara", 1), Distribution("Acne", 2)),
                subcategories = emptyMap(),
                brandSort = BrandSort.ALPHA,
                retired = 1,
                lifespans = emptyList(),
            ),
            model.state.value.view,
        )
    }
}
