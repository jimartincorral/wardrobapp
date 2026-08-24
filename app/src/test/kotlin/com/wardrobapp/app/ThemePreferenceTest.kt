package com.wardrobapp.app

import com.wardrobapp.presentation.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * That the theme choice survives.
 *
 * `ThemeChoiceTest` in :presentation covers what a stored value means; what it
 * cannot cover is whether anything is actually stored, which is the half that
 * needs Android. A setting that reverts on every launch reads as the picker not
 * working at all, and nothing outside a real preferences file would notice.
 */
@RunWith(RobolectricTestRunner::class)
class ThemePreferenceTest {

    private val context = RuntimeEnvironment.getApplication()

    /**
     * Start from a fresh install every time.
     *
     * Explicitly, for the same reason [WardrobeDatabaseLocationTest] empties the
     * data directory: the first assertion below is about what an app that has
     * never been given a choice does, and it would quietly stop testing that if
     * a preference leaked in from another method.
     */
    @Before
    fun forgetAnyChoice() {
        ThemePreference(context).choice = ThemeChoice.SYSTEM
    }

    @Test
    fun `a fresh install follows the device`() {
        assertEquals(ThemeChoice.SYSTEM, ThemePreference(context).choice)
    }

    @Test
    fun `a choice is still there for the next instance`() {
        // A second instance rather than the same one, because that is what the
        // next launch is: the value has to come back from the file, not from a
        // field that happens to still hold it.
        ThemePreference(context).choice = ThemeChoice.DARK

        assertEquals(ThemeChoice.DARK, ThemePreference(context).choice)
    }

    @Test
    fun `going back to automatic clears the choice`() {
        val preference = ThemePreference(context)
        preference.choice = ThemeChoice.LIGHT
        preference.choice = ThemeChoice.SYSTEM

        assertEquals(ThemeChoice.SYSTEM, ThemePreference(context).choice)
    }

    @Test
    fun `a later choice replaces the one before it`() {
        val preference = ThemePreference(context)
        preference.choice = ThemeChoice.DARK
        preference.choice = ThemeChoice.LIGHT

        assertEquals(ThemeChoice.LIGHT, ThemePreference(context).choice)
    }
}
