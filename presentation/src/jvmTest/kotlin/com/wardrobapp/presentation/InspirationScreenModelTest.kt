package com.wardrobapp.presentation

import com.wardrobapp.data.InspirationRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** The looks screen's model: list, add once stored, delete now and put back on failure. */
@OptIn(ExperimentalCoroutinesApi::class)
class InspirationScreenModelTest {

    private class FakeSource : InspirationSource {
        val looks = mutableListOf(InspirationRecord("l1", "photos/one.jpg", "2026-01-01T00:00:00.000Z"))
        var failDelete = false
        val calls = mutableListOf<String>()

        override suspend fun looks(): List<InspirationRecord> = looks.toList()

        override suspend fun add(photo: String): InspirationRecord {
            calls += "add $photo"
            return InspirationRecord("l${looks.size + 1}", photo, "2026-01-02T00:00:00.000Z").also { looks.add(0, it) }
        }

        override suspend fun delete(id: String) {
            calls += "delete $id"
            if (failDelete) throw IllegalStateException("no")
            looks.removeAll { it.id == id }
        }
    }

    private fun TestScope.model(source: FakeSource) = InspirationScreenModel(this, source)

    @Test
    fun `lists the looks, then adds a stored photo to the front`() = runTest(StandardTestDispatcher()) {
        val source = FakeSource()
        val model = model(source)
        advanceUntilIdle()
        assertEquals(listOf("l1"), model.state.value.looks.map { it.id })

        model.onPhotoStored("photos/two.jpg")
        advanceUntilIdle()

        assertEquals(listOf("add photos/two.jpg"), source.calls)
        assertEquals(listOf("l2", "l1"), model.state.value.looks.map { it.id })
        assertFalse(model.state.value.adding)
    }

    @Test
    fun `a delete leaves the grid at once and comes back if it fails`() = runTest(StandardTestDispatcher()) {
        val source = FakeSource().apply { failDelete = true }
        val model = model(source)
        advanceUntilIdle()

        model.onDeleteRequested("l1")
        assertEquals(emptyList(), model.state.value.looks)
        advanceUntilIdle()

        assertEquals(listOf("l1"), model.state.value.looks.map { it.id })
        assertNotNull(model.state.value.error)
    }

    @Test
    fun `a pick that came back with nothing is not busy any more`() = runTest(StandardTestDispatcher()) {
        val model = model(FakeSource())
        advanceUntilIdle()

        model.onAddStarted()
        assertEquals(true, model.state.value.adding)
        model.onAddAbandoned()
        assertFalse(model.state.value.adding)
    }
}
