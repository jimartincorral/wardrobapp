package com.wardrobapp.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.wardrobapp.data.normalizeGarmentRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * That the wardrobe screen can be scrolled with its filters open.
 *
 * The bug this exists for: the filter panel sat above the list inside a plain
 * Column, which hands each child the height it asks for in order. The panel is
 * taller than a phone screen, so it took all of it -- its own last rows off the
 * bottom, and nothing left for the list, which then had no room to scroll in.
 * Both halves of the screen were unreachable at once and neither was missing, so
 * nothing but a scroll could tell.
 *
 * Robolectric's default screen is 320x470dp, small enough that both assertions
 * below need a real scroll to pass.
 */
@RunWith(RobolectricTestRunner::class)
class WardrobeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** Distinguishable by brand: the type on a row goes through the vocabulary. */
    private fun garment(index: Int) = normalizeGarmentRow(
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
            "color_primary" to "#1F3A93",
            "color_palette" to """["#1F3A93"]""",
            "is_available" to 1,
        ),
        "",
    )

    private fun show(state: WardrobeViewModel.State) {
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
                onColorTapped = {},
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
    fun `with the filters shut the list starts at the garments`() {
        // Nothing to scroll past, so the first row is on screen as composed.
        show(wardrobe(filtersExpanded = false))

        compose.onNodeWithText("Brand 01").assertIsDisplayed()
        compose.onNodeWithText("Include things I no longer wear").assertDoesNotExist()
    }
}
