package com.wardrobapp.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading a stored theme choice, and what it means on a given device.
 *
 * Away from the screen because the two mistakes available here are both quiet: a
 * stored value that stops being recognised silently reverts everyone to following
 * the device, and a choice that ignores the device setting shows a light app on a
 * phone in dark mode. Neither looks like a failure.
 *
 * The expectations are `src/theme/index.tsx`'s: `mode === 'system'` resolves
 * through the device's scheme, anything else wins outright, and an unreadable
 * stored value leaves the mode at `system`.
 */
class ThemeChoiceTest {

    @Test
    fun `nothing stored means the device decides`() {
        assertEquals(ThemeChoice.SYSTEM, themeChoiceFor(null))
        assertEquals(ThemeChoice.SYSTEM, themeChoiceFor(""))
        assertEquals(ThemeChoice.SYSTEM, themeChoiceFor("   "))
    }

    @Test
    fun `a stored choice is that choice`() {
        assertEquals(ThemeChoice.LIGHT, themeChoiceFor("light"))
        assertEquals(ThemeChoice.DARK, themeChoiceFor("dark"))
    }

    @Test
    fun `the word system is read as following the device`() {
        // Not a value this app writes -- it stores nothing for SYSTEM -- but it is
        // what the React Native app persists, and a phone that has run both should
        // not be shown a palette neither of them chose.
        assertEquals(ThemeChoice.SYSTEM, themeChoiceFor("system"))
    }

    @Test
    fun `case and surrounding space do not matter`() {
        assertEquals(ThemeChoice.DARK, themeChoiceFor("Dark"))
        assertEquals(ThemeChoice.LIGHT, themeChoiceFor(" LIGHT "))
    }

    @Test
    fun `a value this build does not know falls back to the device`() {
        // A palette added by a later version, read by this one. Following the
        // device is the answer that is never wrong; picking one of the two is a
        // guess that shows as the wrong colours.
        assertEquals(ThemeChoice.SYSTEM, themeChoiceFor("midnight"))
    }

    @Test
    fun `the value written back is the one that would be read`() {
        for (choice in ThemeChoice.entries) {
            val stored = choice.storedValue
            if (stored == null) {
                assertEquals(ThemeChoice.SYSTEM, choice)
            } else {
                assertEquals(choice, themeChoiceFor(stored), "round trip for $choice")
            }
        }
    }

    @Test
    fun `following the device stores nothing rather than a blank`() {
        assertNull(ThemeChoice.SYSTEM.storedValue)
    }

    @Test
    fun `following the device follows it in both directions`() {
        assertTrue(ThemeChoice.SYSTEM.usesDarkColors(systemInDark = true))
        assertFalse(ThemeChoice.SYSTEM.usesDarkColors(systemInDark = false))
    }

    @Test
    fun `a chosen palette overrides the device`() {
        // The whole point of the setting: a phone in dark mode showing a light
        // app because that is what was asked for.
        assertFalse(ThemeChoice.LIGHT.usesDarkColors(systemInDark = true))
        assertTrue(ThemeChoice.DARK.usesDarkColors(systemInDark = false))
    }
}
