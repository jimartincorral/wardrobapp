package com.wardrobapp.presentation

import com.wardrobapp.presentation.StyleModelState.Offer
import com.wardrobapp.presentation.StyleModelState.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the style model's card offers, and when the looks screen warns. */
class StyleModelStateTest {

    private fun state(status: Status, paired: Boolean = false) = StyleModelState(status, bytes = 89_313_414, paired = paired)

    @Test
    fun `the card says the size, as the rest of the app writes megabytes`() {
        assertEquals("85.2", state(Status.Absent).megabytes)
    }

    @Test
    fun `a paired phone is told the download is optional, an unpaired one that it is the feature`() {
        assertEquals(Offer.DOWNLOAD, state(Status.Absent).offer)
        assertEquals(Offer.DOWNLOAD_BESIDE_HOME_ASSISTANT, state(Status.Absent, paired = true).offer)
        assertEquals(Offer.DOWNLOADING, state(Status.Downloading(0.3f)).offer)
        assertEquals(Offer.REMOVE, state(Status.Ready, paired = true).offer)
        assertEquals(Offer.RETRY, state(Status.Failed("no network")).offer)
    }

    @Test
    fun `the looks screen shows the card only where nothing else will read the looks`() {
        assertTrue(state(Status.Absent).showsOnLooks)
        assertTrue(state(Status.Failed(null)).showsOnLooks)
        assertTrue(state(Status.Downloading(null)).showsOnLooks, "the progress stays where the tap was")
        assertFalse(state(Status.Absent, paired = true).showsOnLooks, "Home Assistant reads them")
        assertFalse(state(Status.Ready).showsOnLooks)
    }
}
