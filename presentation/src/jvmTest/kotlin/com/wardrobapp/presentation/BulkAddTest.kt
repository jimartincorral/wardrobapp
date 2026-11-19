package com.wardrobapp.presentation

import com.wardrobapp.domain.GarmentReading
import com.wardrobapp.domain.ReadType
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules of the bulk-add queue, and the reading ahead that goes with it.
 *
 * The queue is only photos and counters now -- what each garment *is* belongs
 * to its form, whose rules GarmentFormTest has -- so what can go wrong here is
 * counting: a photo silently dropped, a summary that reports work nobody did,
 * or "3 of 12" disagreeing with the strip under it.
 */
class BulkAddTest {

    private fun queueOf(vararg photos: String) = BulkAddState().withPhotosAdded(photos.toList())

    @Test
    fun `a fresh screen has not finished anything`() {
        // Otherwise an untouched screen reports a completed job it never began,
        // and the caller sends the user back to the wardrobe before they picked
        // a single photo.
        val state = BulkAddState()

        assertFalse(state.isFinished)
        assertNull(state.next())
    }

    @Test
    fun `the queue is worked through from the front`() {
        val (first, afterFirst) = queueOf("a.jpg", "b.jpg", "c.jpg").next()!!
        val (second, afterSecond) = afterFirst.advanced().next()!!

        assertEquals("a.jpg", first)
        assertEquals("b.jpg", second)
        assertEquals(listOf("c.jpg"), afterSecond.waiting)
    }

    @Test
    fun `taking a photo off the queue is not finishing it`() {
        // The one on screen has left `waiting` but is not done until it is saved
        // or skipped -- otherwise opening the last garment would show the summary
        // over it.
        val (_, onScreen) = queueOf("a.jpg").next()!!

        assertTrue(onScreen.waiting.isEmpty())
        assertFalse(onScreen.isFinished)
        assertTrue(onScreen.advanced().isFinished)
    }

    @Test
    fun `the position counts up as garments are dealt with`() {
        val state = queueOf("a.jpg", "b.jpg", "c.jpg")

        assertEquals(1, state.position)
        assertEquals(2, state.advanced().position)
        assertEquals(3, state.skipped().advanced().position)
    }

    @Test
    fun `more photos join the queue rather than replacing it`() {
        // "Add more" arriving mid-queue must not throw away what is left of the
        // first batch, and the count it is measured against has to grow with it or
        // "5 of 3" follows.
        val state = queueOf("a.jpg", "b.jpg").withPhotosAdded(listOf("c.jpg"))

        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), state.waiting)
        assertEquals(3, state.total)
    }

    @Test
    fun `skipping a photo is not adding a garment`() {
        val state = queueOf("a.jpg", "b.jpg").advanced().skipped()

        assertEquals(1, state.added)
        assertEquals(1, state.skipped)
        assertTrue(state.isFinished)
    }

    @Test
    fun `a queued photo is read once, however often it is asked`() = runTest {
        val photos = CountingPhotos()
        val ahead = ReadAhead(photos, this)

        ahead.readAhead("a.jpg")
        assertEquals(listOf("#112233"), ahead.colors("a.jpg"))
        assertEquals("shoes", ahead.read("a.jpg")?.type?.category)
        ahead.colors("a.jpg")

        assertEquals(1, photos.colorReads)
        assertEquals(1, photos.typeReads)
    }

    @Test
    fun `asking before the reading is done waits for that reading rather than starting another`() = runTest {
        // The form opens on the first photo the moment it lands, which is
        // exactly when its reading has only just begun.
        val photos = CountingPhotos().apply { gate = CompletableDeferred() }
        val ahead = ReadAhead(photos, this)
        ahead.readAhead("a.jpg")

        var read: GarmentReading? = null
        launch { read = ahead.read("a.jpg") }
        advanceUntilIdle()
        assertNull(read)

        photos.gate?.complete(Unit)
        advanceUntilIdle()

        assertEquals("shoes", read?.type?.category)
        assertEquals(1, photos.typeReads)
    }

    @Test
    fun `a photo that cannot be read reads as nothing, and takes nothing down with it`() = runTest {
        // A failed Deferred in the screen's scope would cancel the scope, and
        // every form on it.
        val photos = CountingPhotos().apply { failing = true }
        val ahead = ReadAhead(photos, this)

        ahead.readAhead("a.jpg")
        advanceUntilIdle()

        assertNull(ahead.colors("a.jpg"))
        assertNull(ahead.read("a.jpg"))
    }

    private class CountingPhotos : PhotoWork<String> {
        var colorReads = 0
        var typeReads = 0
        var failing = false
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun store(photo: String) = photo
        override suspend fun delete(photo: String) = Unit
        override suspend fun cutOut(photo: String) = "$photo.nobg"

        override suspend fun colors(photo: String): List<String>? {
            colorReads++
            if (failing) throw IOException("unreadable")
            return listOf("#112233")
        }

        override suspend fun read(photo: String): GarmentReading? {
            typeReads++
            gate?.await()
            if (failing) throw IOException("unreadable")
            return GarmentReading(type = ReadType("shoes", "Boots"))
        }
    }
}
