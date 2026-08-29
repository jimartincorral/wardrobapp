package com.wardrobapp.app

import android.content.Context
import com.wardrobapp.presentation.DEFAULT_GRID_COLUMNS
import com.wardrobapp.presentation.GarmentCaption
import com.wardrobapp.presentation.WardrobeLayout
import com.wardrobapp.presentation.WardrobeView
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * That the wardrobe's layout survives.
 *
 * `WardrobeViewTest` in :presentation covers what a stored pair means; this is the
 * half that needs Android -- whether anything is stored at all. The same split as
 * [ThemePreferenceTest], and for the same reason: a choice that reverts on every
 * launch reads as a menu that does nothing.
 */
@RunWith(RobolectricTestRunner::class)
class WardrobeViewPreferenceTest {

    private val context = RuntimeEnvironment.getApplication()

    /** Back to a fresh install, so the first assertion keeps testing that. */
    @Before
    fun forgetAnyChoice() {
        WardrobeViewPreference(context).view = WardrobeView()
        WardrobeViewPreference(context).caption = GarmentCaption.BRAND
    }

    @Test
    fun `a fresh install is the list this app has always had`() {
        assertEquals(WardrobeView(), WardrobeViewPreference(context).view)
    }

    @Test
    fun `a grid is still there for the next launch`() {
        // A second instance, because that is what the next launch is: the value has
        // to come back from the file rather than from a field still holding it.
        WardrobeViewPreference(context).view = WardrobeView(WardrobeLayout.GRID, columns = 4)

        assertEquals(
            WardrobeView(WardrobeLayout.GRID, columns = 4),
            WardrobeViewPreference(context).view,
        )
    }

    @Test
    fun `going back to the list keeps the width the grid had`() {
        val preference = WardrobeViewPreference(context)
        preference.view = WardrobeView(WardrobeLayout.GRID, columns = 2)
        preference.view = WardrobeView(WardrobeLayout.LIST, columns = 2)

        val stored = WardrobeViewPreference(context).view
        assertEquals(WardrobeLayout.LIST, stored.layout)
        // Stored rather than defaulted, so choosing the grid again is the grid that
        // was left, not the middle one.
        assertEquals(2, stored.columns)
    }

    @Test
    fun `a fresh install captions its cells the way this app always has`() {
        assertEquals(GarmentCaption.BRAND, WardrobeViewPreference(context).caption)
    }

    @Test
    fun `a chosen caption is still there for the next launch`() {
        WardrobeViewPreference(context).caption = GarmentCaption.CATEGORY

        assertEquals(GarmentCaption.CATEGORY, WardrobeViewPreference(context).caption)
    }

    @Test
    fun `going back to the brand leaves nothing stored`() {
        // The brand is recorded as the absence of a choice, so this is the case
        // where the key has to be removed rather than written: an install that has
        // never chosen and one that chose the brand have to read alike.
        val preference = WardrobeViewPreference(context)
        preference.caption = GarmentCaption.TYPE
        preference.caption = GarmentCaption.BRAND

        assertEquals(GarmentCaption.BRAND, WardrobeViewPreference(context).caption)
        assertEquals(
            null,
            context
                .getSharedPreferences(APPEARANCE_PREFERENCES, Context.MODE_PRIVATE)
                .getString("wardrobe_caption", null),
        )
    }

    @Test
    fun `the caption and the layout do not overwrite each other`() {
        val preference = WardrobeViewPreference(context)
        preference.view = WardrobeView(WardrobeLayout.GRID, columns = 4)
        preference.caption = GarmentCaption.TYPE

        val next = WardrobeViewPreference(context)
        assertEquals(WardrobeView(WardrobeLayout.GRID, columns = 4), next.view)
        assertEquals(GarmentCaption.TYPE, next.caption)
    }

    @Test
    fun `an empty file is not read as a grid of nothing`() {
        // SharedPreferences answers a missing int with whatever default it is given,
        // and zero is not a column count -- untreated it would snap to the narrowest
        // grid and quietly override the default width.
        assertEquals(DEFAULT_GRID_COLUMNS, WardrobeViewPreference(context).view.columns)
    }
}
