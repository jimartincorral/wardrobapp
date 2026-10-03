package com.wardrobapp.presentation

import java.io.IOException
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WardrobeScreenModelTest {

    private class FakeSource : WardrobeSource {
        val asked = mutableListOf<WardrobeQuery>()
        var fail: Exception? = null
        override suspend fun garments(query: WardrobeQuery) =
            fail?.let { throw it } ?: listOf(testGarment("g1", "tops"), testGarment("g2", "bottoms"))
                .also { asked += query }
    }

    private class MemorySettings(
        override var view: WardrobeView = WardrobeView(),
        override var caption: GarmentCaption = GarmentCaption.BRAND,
    ) : WardrobeViewSettings

    @Test
    fun `starts with this device's layout and loads the wardrobe`() = runTest {
        val settings = MemorySettings(view = WardrobeView(WardrobeLayout.GRID, 3), caption = GarmentCaption.TYPE)
        val model = WardrobeScreenModel(this, FakeSource(), settings)
        assertEquals(WardrobeView(WardrobeLayout.GRID, 3), model.state.value.view)
        assertEquals(GarmentCaption.TYPE, model.state.value.caption)

        advanceUntilIdle()

        assertFalse(model.state.value.loading)
        assertEquals(listOf("g1", "g2"), model.state.value.garments.map { it.id })
    }

    @Test
    fun `a tapped filter reloads at once with the narrower query`() = runTest {
        val source = FakeSource()
        val model = WardrobeScreenModel(this, source, MemorySettings())
        advanceUntilIdle()

        model.onCategoryTapped("tops")
        runCurrent()

        assertEquals("tops", source.asked.last().category)
    }

    @Test
    fun `typing waits for a pause, and reads once for the whole word`() = runTest {
        val source = FakeSource()
        val model = WardrobeScreenModel(this, source, MemorySettings())
        advanceUntilIdle()
        val before = source.asked.size

        for (prefix in listOf("j", "ja", "jac", "jack")) {
            model.onSearchChanged(prefix)
            advanceTimeBy(WardrobeScreenModel.TYPING_PAUSE_MS - 50)
        }
        assertEquals("jack", model.state.value.query.search, "the box shows what was typed at once")
        assertEquals(before, source.asked.size, "nothing read while the typing goes on")

        advanceTimeBy(100)
        runCurrent()
        assertEquals(before + 1, source.asked.size)
        assertEquals("jack", source.asked.last().searchTerm)
    }

    @Test
    fun `a new layout is kept for next time without re-reading the wardrobe`() = runTest {
        val source = FakeSource()
        val settings = MemorySettings()
        val model = WardrobeScreenModel(this, source, settings)
        advanceUntilIdle()
        val before = source.asked.size

        model.onViewSelected(WardrobeView(WardrobeLayout.GRID, 4))
        model.onCaptionSelected(GarmentCaption.CATEGORY)
        advanceUntilIdle()

        assertEquals(WardrobeView(WardrobeLayout.GRID, 4), settings.view)
        assertEquals(GarmentCaption.CATEGORY, settings.caption)
        assertEquals(WardrobeView(WardrobeLayout.GRID, 4), model.state.value.view)
        assertEquals(before, source.asked.size)
    }

    @Test
    fun `a link's query replaces the current one`() = runTest {
        val source = FakeSource()
        val model = WardrobeScreenModel(this, source, MemorySettings())
        advanceUntilIdle()
        model.onCategoryTapped("tops")
        advanceUntilIdle()

        model.onQueryRequested(WardrobeQuery(includeRetired = true))
        advanceUntilIdle()

        assertNull(source.asked.last().category)
        assertEquals(true, source.asked.last().includeRetired)
    }

    @Test
    fun `a failed read is reported`() = runTest {
        val source = FakeSource().apply { fail = IOException("locked") }
        val model = WardrobeScreenModel(this, source, MemorySettings())
        advanceUntilIdle()

        assertFalse(model.state.value.loading)
        assertEquals("locked", model.state.value.error)
    }
}
