package com.wardrobapp.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileChoiceTest {

    private val ids = listOf("main", "ana")

    @Test
    fun `a Home Assistant user's own profile opens, whatever was opened last`() {
        assertEquals("ana", profileToOpen(ids, yours = "ana", signedIn = true, lastOpened = "main"))
    }

    @Test
    fun `a Home Assistant user without one is asked, even when there is only one`() {
        assertNull(profileToOpen(ids, yours = null, signedIn = true, lastOpened = "main"))
        assertNull(profileToOpen(listOf("main"), yours = null, signedIn = true, lastOpened = null))
    }

    @Test
    fun `a profile that is gone is not opened`() {
        assertNull(profileToOpen(ids, yours = "removed", signedIn = true, lastOpened = null))
        assertNull(profileToOpen(ids, yours = null, signedIn = false, lastOpened = "removed"))
    }

    @Test
    fun `nobody signed in opens the last one, or the only one`() {
        assertEquals("ana", profileToOpen(ids, yours = null, signedIn = false, lastOpened = "ana"))
        assertEquals("main", profileToOpen(listOf("main"), yours = null, signedIn = false, lastOpened = null))
        assertNull(profileToOpen(ids, yours = null, signedIn = false, lastOpened = null))
    }
}
