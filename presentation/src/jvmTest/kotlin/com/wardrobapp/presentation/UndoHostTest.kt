package com.wardrobapp.presentation

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The one undo line: what stands, for how long, and what happens either way. */
class UndoHostTest {

    @Test
    fun `an offer stands for the window, then expires`() = runTest {
        val host = UndoHost(this, windowMillis = 1_000)
        val events = mutableListOf<String>()

        host.offer(Deleted.GARMENT, undo = { events += "undo"; true }, expire = { events += "expire" })
        assertEquals(Deleted.GARMENT, host.offer.value?.deleted)

        advanceTimeBy(999)
        assertEquals(emptyList(), events)
        advanceTimeBy(2)
        advanceUntilIdle()
        assertNull(host.offer.value)
        assertEquals(listOf("expire"), events)
        assertEquals(0, host.restored.value)
    }

    @Test
    fun `undo runs the undo, not the expiry, and counts`() = runTest {
        val host = UndoHost(this, windowMillis = 1_000)
        val events = mutableListOf<String>()

        host.offer(Deleted.OUTFIT, undo = { events += "undo"; true }, expire = { events += "expire" })
        host.onUndo()
        advanceUntilIdle()

        assertNull(host.offer.value)
        assertEquals(listOf("undo"), events)
        assertEquals(1, host.restored.value)

        // Too late to undo -- the source had nothing -- is not a restoration.
        host.offer(Deleted.OUTFIT, undo = { events += "undo-late"; false })
        host.onUndo()
        advanceUntilIdle()
        assertEquals(1, host.restored.value)
    }

    @Test
    fun `a second offer expires the first, and a dismissal expires the one standing`() = runTest {
        val host = UndoHost(this, windowMillis = 1_000)
        val events = mutableListOf<String>()

        host.offer(Deleted.GARMENT, undo = { true }, expire = { events += "expire-1" })
        advanceTimeBy(500)
        host.offer(Deleted.OUTFIT, undo = { true }, expire = { events += "expire-2" })
        advanceUntilIdle()
        // The second's own window; with the test scope idle the first was
        // expired at once and the second has run out too.
        assertEquals(listOf("expire-1", "expire-2"), events)

        host.offer(Deleted.GARMENT, undo = { true }, expire = { events += "expire-3" })
        host.onDismissed()
        advanceUntilIdle()
        assertNull(host.offer.value)
        assertEquals(listOf("expire-1", "expire-2", "expire-3"), events)
    }
}
