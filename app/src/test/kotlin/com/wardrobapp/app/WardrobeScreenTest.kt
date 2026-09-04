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
import com.wardrobapp.domain.Season
import com.wardrobapp.presentation.GarmentCaption
import com.wardrobapp.presentation.GarmentFilter
import com.wardrobapp.presentation.WardrobeLayout
import com.wardrobapp.presentation.WardrobeView
import com.wardrobapp.presentation.filterBy
import com.wardrobapp.presentation.wardrobeFacets
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
    private fun garment(
        index: Int,
        palette: String = "#1F3A93",
        brand: String = "Brand %02d".format(index),
    ) = normalizeGarmentRow(
        mapOf(
            "id" to "g$index",
            "image_uri" to "",
            "image_uris" to "[]",
            "image_uris_nobg" to "[]",
            "category" to "tops",
            "subcategories" to """["T-Shirt"]""",
            "tags" to """["row-%02d"]""".format(index),
            "brand" to brand,
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
        onViewSelected: (WardrobeView) -> Unit = {},
        onCaptionSelected: (GarmentCaption) -> Unit = {},
        onBrandTapped: (String) -> Unit = {},
    ) {
        compose.setContent {
            WardrobeScreen(
                state = state,
                onSearchChanged = {},
                onSortToggled = {},
                onRetry = {},
                onGarmentOpened = {},
                onAddRequested = {},
                onBulkAddRequested = {},
                onFiltersToggled = {},
                onFiltersCleared = {},
                onBrandTapped = onBrandTapped,
                onSizeTapped = {},
                onCategoryTapped = {},
                onSubcategoryTapped = {},
                onSeasonTapped = {},
                onOccasionTapped = {},
                onColorTapped = onColorTapped,
                onRetiredToggled = {},
                onViewSelected = onViewSelected,
                onCaptionSelected = onCaptionSelected,
            )
        }
    }

    private fun wardrobe(
        filtersExpanded: Boolean = false,
        view: WardrobeView = WardrobeView(),
        caption: GarmentCaption = GarmentCaption.BRAND,
        garments: List<com.wardrobapp.data.GarmentRecord> = (1..12).map { garment(it) },
        query: com.wardrobapp.presentation.WardrobeQuery = com.wardrobapp.presentation.WardrobeQuery(),
    ) = WardrobeViewModel.State(
        loading = false,
        garments = garments,
        query = query,
        // Through the real facet rule, because the panel now draws what the
        // wardrobe holds: a chip nothing in this list wears must not appear, and a
        // test that hand-wrote the facets would not notice either way.
        facets = wardrobeFacets(garments, query),
        filtersExpanded = filtersExpanded,
        view = view,
        caption = caption,
    )

    @Test
    fun `what is narrowing the list is on screen with the sheet shut`() {
        // The bar says "how many" on a badge and nothing else. With the sheet
        // closed this row is the only thing on the screen that says the list is
        // fourteen garments out of a hundred rather than a hundred -- and it is
        // the whole reason the old spelled-out "Filters (2)" button could be
        // replaced by a glyph.
        show(
            wardrobe(
                query = com.wardrobapp.presentation.WardrobeQuery(
                    category = "tops",
                    season = Season.SUMMER,
                ),
            ),
        )

        compose.onNodeWithTag(appliedFilterTag("Tops")).assertIsDisplayed()
        compose.onNodeWithTag(appliedFilterTag("Summer")).assertIsDisplayed()
    }

    @Test
    fun `an applied chip undoes the filter it names`() {
        // Tapping the chip is the short way back, and it has to send the same
        // value the sheet would: the callbacks are what the wardrobe re-reads on.
        var dropped: String? = null
        show(
            wardrobe(query = com.wardrobapp.presentation.WardrobeQuery(brand = "Uniqlo")),
            onBrandTapped = { dropped = it },
        )

        compose.onNodeWithTag(appliedFilterTag("Uniqlo")).performClick()

        assertEquals("Uniqlo", dropped)
    }

    @Test
    fun `nothing narrowing the list means no chips at all`() {
        // A row that is always there, empty, is a row that costs a line of the
        // screen to say nothing.
        show(wardrobe())

        compose.onNodeWithTag(appliedFilterTag("Tops")).assertDoesNotExist()
        compose.onNodeWithText("Clear all").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h2000dp")
    fun `the sheet says how many garments are behind it`() {
        // The footer button is the sheet's own dismissal *and* the answer to what
        // the filters did. A count that disagreed with the list would make the
        // button a lie, so it is the same list the screen is drawing.
        show(wardrobe(filtersExpanded = true, garments = (1..3).map { garment(it) }))

        compose.onNodeWithText("Show 3 garments").assertIsDisplayed()
    }

    @Test
    fun `garments can be reached with the filters open`() {
        show(wardrobe(filtersExpanded = true))

        // By its tag rather than its brand: the panel offers this wardrobe's brands
        // as chips now, so "Brand 12" is two nodes -- the chip and the row -- and a
        // matcher that finds both is not asking about either. A tag appears on the
        // row alone.
        compose.onNodeWithTag(WARDROBE_LIST).performScrollToNode(hasText("row-12"))

        compose.onNodeWithText("row-12").assertIsDisplayed()
    }

    @Test
    fun `the last of the filters can be reached too`() {
        // The other half of the same bug. This row is the bottom of the panel, and
        // the only way to see a retired garment again -- so the panel running off
        // the screen made retiring a one-way door for a second time.
        show(wardrobe(filtersExpanded = true))

        compose.onNodeWithTag(WARDROBE_FILTER_SHEET)
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
        val gold = garment(1, palette = "#DAA520")
        show(
            wardrobe(filtersExpanded = true, garments = listOf(gold)),
            onColorTapped = { picked = it },
        )

        compose.onNodeWithTag(WARDROBE_FILTER_SHEET)
            .performScrollToNode(hasTestTag(colorSwatchTag("#DAA520")))
        compose.onNodeWithTag(colorSwatchTag("#DAA520")).performClick()

        assertEquals("#DAA520", picked)
        // And through the real predicate, so this stays honest if either side moves.
        assertEquals(
            listOf("g1"),
            listOf(gold).filterBy(GarmentFilter(color = picked)).map { it.id },
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h2000dp")
    fun `the panel offers the brands and sizes the wardrobe has, and no others`() {
        // What this replaces: two text boxes you typed a brand into from memory,
        // and rows offering every category and all twenty-five palette colours,
        // most of which matched nothing in any particular wardrobe.
        var picked: String? = null
        show(
            wardrobe(
                filtersExpanded = true,
                garments = listOf(garment(1, brand = "Uniqlo"), garment(2, brand = "Arket")),
            ),
            onBrandTapped = { picked = it },
        )

        // By tag rather than by text: these garments wear these brands, so each
        // name is on screen twice -- as a chip and on its row -- and only one of
        // them is the chip this test is about.
        compose.onNodeWithTag(filterChipTag("Uniqlo")).assertIsDisplayed()
        compose.onNodeWithTag(filterChipTag("Arket")).assertIsDisplayed()
        // A brand this wardrobe does not hold is not on offer, whatever else does.
        compose.onNodeWithTag(filterChipTag("Nike")).assertDoesNotExist()

        compose.onNodeWithTag(filterChipTag("Uniqlo")).performClick()
        assertEquals("Uniqlo", picked)
    }

    @Test
    fun `a colour swatch carries the palette's name for it`() {
        // A circle in a shade nobody can name from memory used to be the whole
        // chip. Every other filter here -- brand, size, season -- reads as a
        // word; a colour is now the same, or it is the one filter you cannot use
        // without already knowing what you are looking for.
        show(wardrobe(filtersExpanded = true, garments = listOf(garment(1, palette = "#000080"))))

        compose.onNodeWithTag(WARDROBE_FILTER_SHEET)
            .performScrollToNode(hasTestTag(colorSwatchTag("#000080")))
        compose.onNodeWithTag(colorSwatchTag("#000080")).assertIsDisplayed()
        compose.onNodeWithText("Navy").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h2000dp")
    fun `a colour no garment wears is not offered`() {
        // The whole wardrobe is navy, so the panel is one swatch rather than the
        // app's entire palette.
        show(wardrobe(filtersExpanded = true, garments = listOf(garment(1, palette = "#000080"))))

        compose.onNodeWithTag(colorSwatchTag("#000080")).assertExists()
        compose.onNodeWithTag(colorSwatchTag("#DAA520")).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h2000dp")
    fun `the view menu offers the list and every grid width`() {
        show(wardrobe())

        compose.onNodeWithTag(WARDROBE_VIEW_MENU).performClick()

        // A row per density, tagged by how many cells it asks for -- a list being
        // zero of them. By tag rather than by label, because a label is now two
        // resources joined ("Large - 2 per row") and a test that hard-codes the
        // join is testing the format string rather than the menu.
        compose.onNodeWithTag(wardrobeSizeTag(0)).assertIsDisplayed()
        compose.onNodeWithTag(wardrobeSizeTag(2)).assertIsDisplayed()
        compose.onNodeWithTag(wardrobeSizeTag(3)).assertIsDisplayed()
        compose.onNodeWithTag(wardrobeSizeTag(4)).assertIsDisplayed()

        // And one of them read out in full, so the join is checked once rather
        // than not at all.
        compose.onNodeWithText("Large — 2 per row").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h2000dp")
    fun `picking a grid asks for that many columns`() {
        var picked: WardrobeView? = null
        show(wardrobe(), onViewSelected = { picked = it })

        compose.onNodeWithTag(WARDROBE_VIEW_MENU).performClick()
        compose.onNodeWithTag(wardrobeSizeTag(2)).performClick()

        assertEquals(WardrobeView(WardrobeLayout.GRID, columns = 2), picked)
    }

    @Test
    fun `a grid still says whose garment each photo is`() {
        // The one thing a cell adds to a photo, and the reason a grid is navigable
        // at all: a wall of 3:4 photos with no captions is a wall of photos.
        show(wardrobe(view = WardrobeView(WardrobeLayout.GRID, columns = 2)))

        compose.onNodeWithText("Brand 01").assertIsDisplayed()
    }

    @Test
    fun `a cell says what was asked for rather than always the brand`() {
        // The whole point of the setting: this garment has a brand, and asking for
        // the category has to win over it.
        show(
            wardrobe(
                view = WardrobeView(WardrobeLayout.GRID, columns = 2),
                caption = GarmentCaption.CATEGORY,
                garments = listOf(garment(1)),
            ),
        )

        compose.onNodeWithText("Tops").assertIsDisplayed()
        compose.onNodeWithText("Brand 01").assertDoesNotExist()
    }

    @Test
    fun `a cell asked for the type names it in the reader's language`() {
        // Through the vocabulary rather than as stored: the row holds "T-Shirt"
        // whichever language entered it.
        show(
            wardrobe(
                view = WardrobeView(WardrobeLayout.GRID, columns = 2),
                caption = GarmentCaption.TYPE,
                garments = listOf(garment(1)),
            ),
        )

        compose.onNodeWithText("T-Shirt").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h2000dp")
    fun `the caption is offered for a grid and not for a list`() {
        // A row already shows the type as its title and the brand under it, so
        // there is nothing there to choose between.
        show(wardrobe(view = WardrobeView(WardrobeLayout.GRID, columns = 2)))

        compose.onNodeWithTag(WARDROBE_VIEW_MENU).performClick()

        compose.onNodeWithText("Brand").assertIsDisplayed()
        compose.onNodeWithText("Category").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h2000dp")
    fun `a list is not offered a caption to choose`() {
        show(wardrobe(view = WardrobeView(WardrobeLayout.LIST)))

        compose.onNodeWithTag(WARDROBE_VIEW_MENU).performClick()

        compose.onNodeWithText("Brand").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h2000dp")
    fun `picking a caption asks for that field`() {
        var picked: GarmentCaption? = null
        show(
            wardrobe(view = WardrobeView(WardrobeLayout.GRID, columns = 2)),
            onCaptionSelected = { picked = it },
        )

        compose.onNodeWithTag(WARDROBE_VIEW_MENU).performClick()
        compose.onNodeWithText("Category").performClick()

        assertEquals(GarmentCaption.CATEGORY, picked)
    }

    @Test
    fun `with the filters shut the list starts at the garments`() {
        // Nothing to scroll past, so the first row is on screen as composed.
        show(wardrobe(filtersExpanded = false))

        compose.onNodeWithText("Brand 01").assertIsDisplayed()
        compose.onNodeWithText("Include things I no longer wear").assertDoesNotExist()
    }
}
