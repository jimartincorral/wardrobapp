package com.wardrobapp.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.wardrobapp.data.normalizeGarmentRow
import com.wardrobapp.presentation.GarmentFilter
import com.wardrobapp.presentation.filterBy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The wardrobe screen's filter panel: that it can be scrolled, and that what it
 * sends is what the wardrobe can be filtered by.
 *
 * Two shipped bugs, both in the seam between this screen and code that was already
 * tested on its own.
 *
 * The panel sat above the list inside a plain Column, which hands each child the
 * height it asks for in order. The panel is taller than a phone screen, so it took
 * all of it -- its own last rows off the bottom, and nothing left for the list,
 * which then had no room to scroll in. Both halves were unreachable at once and
 * neither was missing, so nothing but a scroll could tell.
 *
 * And a colour swatch sent the palette's key while the predicate matches a
 * garment's stored hex, so every colour answered "nothing matches these filters".
 * The predicate had a test, the palette had a test, and the pair had none.
 *
 * Robolectric's default screen is 320x470dp, small enough that these need a real
 * scroll to reach what they assert on.
 */
@RunWith(RobolectricTestRunner::class)
class WardrobeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** Distinguishable by brand: the type on a row goes through the vocabulary. */
    private fun garment(index: Int, palette: String = "#1F3A93") = normalizeGarmentRow(
        mapOf(
            "id" to "g$index",
            "image_uri" to "",
            "image_uris" to "[]",
            "image_uris_nobg" to "[]",
            "category" to "tops",
            "subcategories" to """["T-Shirt"]""",
            "tags" to "[]",
            "brand" to "Brand %02d".format(index),
            "size" to "M",
            "color_primary" to palette,
            "color_palette" to """["$palette"]""",
            "is_available" to 1,
        ),
        "",
    )

    private fun show(
        state: WardrobeViewModel.State,
        onColorTapped: (String) -> Unit = {},
    ) {
        compose.setContent {
            WardrobeScreen(
                state = state,
                onSearchChanged = {},
                onSortToggled = {},
                onRetry = {},
                onGarmentOpened = {},
                onAddRequested = {},
                onSettingsRequested = {},
                onFiltersToggled = {},
                onFiltersCleared = {},
                onBrandChanged = {},
                onSizeChanged = {},
                onCategoryTapped = {},
                onSubcategoryTapped = {},
                onSeasonTapped = {},
                onOccasionTapped = {},
                onColorTapped = onColorTapped,
                onRetiredToggled = {},
            )
        }
    }

    private fun wardrobe(filtersExpanded: Boolean) = WardrobeViewModel.State(
        loading = false,
        garments = (1..12).map(::garment),
        filtersExpanded = filtersExpanded,
    )

    @Test
    fun `garments can be reached with the filters open`() {
        show(wardrobe(filtersExpanded = true))

        compose.onNodeWithTag(WARDROBE_LIST).performScrollToNode(hasText("Brand 12"))

        compose.onNodeWithText("Brand 12").assertIsDisplayed()
    }

    @Test
    fun `the last of the filters can be reached too`() {
        // The other half of the same bug. This row is the bottom of the panel, and
        // the only way to see a retired garment again -- so the panel running off
        // the screen made retiring a one-way door for a second time.
        show(wardrobe(filtersExpanded = true))

        compose.onNodeWithTag(WARDROBE_LIST)
            .performScrollToNode(hasText("Include things I no longer wear"))

        compose.onNodeWithText("Include things I no longer wear").assertIsDisplayed()
    }

    @Test
    // On a screen tall enough to hold the open panel, because this asserts which
    // value a swatch sends rather than whether it can be reached: the panel is one
    // list item, so scrolling to it puts its top at the fold and leaves a swatch
    // near its bottom outside the window a tap can land in.
    @Config(qualifiers = "w411dp-h2000dp")
    fun `tapping a colour asks for the hex a garment stores`() {
        // The bug this covers: the panel sent the palette's key and the predicate
        // compares against a garment's palette, which holds hex -- so every colour
        // matched nothing and the screen said so. Both halves were right on their
        // own; nothing tested the join, which is why it shipped.
        var picked: String? = null
        show(wardrobe(filtersExpanded = true), onColorTapped = { picked = it })

        compose.onNodeWithTag(WARDROBE_LIST)
            .performScrollToNode(hasTestTag(colorSwatchTag("gold")))
        compose.onNodeWithTag(colorSwatchTag("gold")).performClick()

        assertEquals("#DAA520", picked)
        // And through the real predicate, so this stays honest if either side moves.
        assertEquals(
            listOf("g1"),
            listOf(garment(1, palette = "#DAA520")).filterBy(GarmentFilter(color = picked)).map { it.id },
        )
    }

    @Test
    fun `with the filters shut the list starts at the garments`() {
        // Nothing to scroll past, so the first row is on screen as composed.
        show(wardrobe(filtersExpanded = false))

        compose.onNodeWithText("Brand 01").assertIsDisplayed()
        compose.onNodeWithText("Include things I no longer wear").assertDoesNotExist()
    }
}
