package com.wardrobapp.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.wardrobapp.data.DuplicateGarmentGroup
import com.wardrobapp.data.normalizeGarmentRow
import com.wardrobapp.domain.DuplicateReason
import com.wardrobapp.presentation.BrandSort
import com.wardrobapp.presentation.Distribution
import com.wardrobapp.presentation.LifespanEntry
import com.wardrobapp.presentation.WardrobeLink
import com.wardrobapp.presentation.statisticsView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The statistics page, which used to be two pages.
 *
 * What the merge has to get right is not arithmetic -- `StatisticsViewTest` and
 * `LifespansTest` cover that -- but that everything both screens showed is still
 * reachable from the one page, and that opening it does not bury the numbers under
 * six charts. So: the tiles are on screen without a tap, the breakdowns are not,
 * and a heading brings its own back.
 */
@RunWith(RobolectricTestRunner::class)
class StatisticsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun view(
        inUse: Long = 4,
        retired: Long = 3,
        categories: List<Distribution> = listOf(Distribution("tops", 3), Distribution("shoes", 1)),
        colors: List<Distribution> = listOf(Distribution("#000000", 2)),
        brands: List<Distribution> = listOf(Distribution("Uniqlo", 3), Distribution("Arket", 1)),
        lifespans: List<LifespanEntry> = listOf(
            LifespanEntry("g1", "tops", listOf("Coat"), days = 400)
        ),
    ) = statisticsView(
        inUse = inUse,
        categories = categories,
        colors = colors,
        brands = brands,
        subcategories = emptyMap(),
        brandSort = BrandSort.COUNT,
        retired = retired,
        lifespans = lifespans,
    )

    private var tapped: StatisticsSection? = null
    private var opened: String? = null
    private var linked: WardrobeLink? = null

    private fun show(
        state: StatisticsViewModel.State = StatisticsViewModel.State(loading = false, view = view()),
    ) {
        compose.setContent {
            StatisticsScreen(
                state = state,
                onCategoryTapped = {},
                onLinkRequested = { linked = it },
                onGarmentOpened = { opened = it },
                onBrandSortChanged = {},
                onSectionTapped = { tapped = it },
                onRetry = {},
            )
        }
    }

    /**
     * Bring a node into view first.
     *
     * Six tiles and four headings do not fit the 320x470dp screen these run on, and
     * a lazy list has not composed what is below the fold -- which reads exactly
     * like a heading that is missing. This is what the first run of these tests
     * found, on the four assertions that live further down the page.
     */
    private fun scrollTo(text: String) = compose.onNodeWithTag(STATISTICS_PAGE)
        .performScrollToNode(hasText(text, substring = true))

    @Test
    fun `the tiles are the page, and the breakdowns are shut`() {
        show()

        // Items, in use, retired: the whole wardrobe and its two halves.
        compose.onNodeWithText("7").assertIsDisplayed()
        compose.onNodeWithText("4").assertIsDisplayed()
        compose.onNodeWithText("3").assertIsDisplayed()

        // Every section is offered, and none of them has unrolled: a brand is
        // named nowhere until its heading is tapped.
        compose.onNodeWithText("By category").assertIsDisplayed()
        compose.onNodeWithText("By colour").assertIsDisplayed()

        scrollTo("By brand")
        compose.onNodeWithText("By brand").assertIsDisplayed()

        scrollTo("How long things lasted")
        compose.onNodeWithText("How long things lasted").assertIsDisplayed()

        // Existence, not visibility: a shut section composes nothing at all, so no
        // amount of scrolling would find a brand.
        compose.onNodeWithText("Uniqlo").assertDoesNotExist()
    }

    @Test
    fun `tapping a heading asks for that section`() {
        show()

        scrollTo("By brand")
        compose.onNodeWithText("By brand").performClick()

        assertEquals(StatisticsSection.BRAND, tapped)
    }

    @Test
    fun `an open section shows its bars`() {
        show(
            StatisticsViewModel.State(
                loading = false,
                view = view(),
                openSections = setOf(StatisticsSection.BRAND),
            )
        )

        // As typed, not capitalized: a brand is what the wearer wrote.
        scrollTo("Uniqlo")
        compose.onNodeWithText("Uniqlo").assertIsDisplayed()
        compose.onNodeWithText("Arket").assertIsDisplayed()

        // And the sort chips travel with the section rather than sitting beside a
        // heading that is now a button.
        scrollTo("A-Z")
        compose.onNodeWithText("A-Z").assertIsDisplayed()
    }

    @Test
    fun `each category offers to show itself in the wardrobe`() {
        // The join this feature is: the button has to report the category *key*,
        // because that is what the wardrobe filters on. Reporting the label -- the
        // translated word next to the bar -- would filter by "Tops" and match
        // nothing, and on a Spanish phone it would match nothing differently.
        show(
            StatisticsViewModel.State(
                loading = false,
                view = view(),
                openSections = setOf(StatisticsSection.CATEGORY),
            )
        )

        scrollTo("Tops")
        compose.onNodeWithTag(statFilterTag("tops")).performClick()

        assertEquals(WardrobeLink.Category("tops"), linked)
    }

    @Test
    fun `a shut category section offers nothing to filter by`() {
        // The buttons live on the rows, so a section nobody has opened has none of
        // them -- which is also what stops the page from being a column of icons.
        show()

        compose.onNodeWithTag(statFilterTag("tops")).assertDoesNotExist()
    }

    @Test
    fun `lifespans are offered even with nothing retired`() {
        // The section the old split hid: with lifespans on another screen, a
        // wardrobe with nothing retired looked like an app that does not measure
        // them. Here the heading is always there and says so when opened.
        show(
            StatisticsViewModel.State(
                loading = false,
                view = view(retired = 0, lifespans = emptyList()),
                openSections = setOf(StatisticsSection.LIFESPAN),
            )
        )

        scrollTo("Nothing retired yet")
        compose.onNodeWithText("Nothing retired yet", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a wardrobe with nothing in it says so instead of listing sections`() {
        show(
            StatisticsViewModel.State(
                loading = false,
                view = view(
                    inUse = 0,
                    retired = 0,
                    categories = emptyList(),
                    colors = emptyList(),
                    brands = emptyList(),
                    lifespans = emptyList(),
                ),
            )
        )

        compose.onNodeWithText("Nothing to measure yet").assertIsDisplayed()
        compose.onNodeWithText("By category").assertDoesNotExist()
        compose.onNodeWithText("How long things lasted").assertDoesNotExist()
    }
    private fun garment(id: String) = normalizeGarmentRow(
        mapOf(
            "id" to id,
            "image_uri" to "$id.jpg",
            "image_uris" to """["$id.jpg"]""",
            "image_uris_nobg" to "[]",
            "category" to "tops",
            "subcategories" to """["T-Shirt"]""",
            "tags" to "[]",
            "color_primary" to "#000000",
            "color_palette" to """["#000000"]""",
            "is_available" to 1,
        ),
        "",
    )

    private fun withDuplicates(open: Boolean) = StatisticsViewModel.State(
        loading = false,
        view = view(),
        openSections = if (open) setOf(StatisticsSection.DUPLICATES) else emptySet(),
        duplicates = listOf(
            DuplicateGarmentGroup(
                garments = listOf(garment("g1"), garment("g2"), garment("g3")),
                reasons = listOf(DuplicateReason.SIMILAR_COLOR, DuplicateReason.SAME_SIZE),
            ),
        ),
    )

    @Test
    fun `the duplicates section is offered, and shut like the rest`() {
        show(withDuplicates(open = false))

        scrollTo("Things you may own twice")
        compose.onNodeWithText("Things you may own twice").assertIsDisplayed()

        // Shut composes nothing, so the photos are absent rather than merely
        // scrolled past.
        compose.onNodeWithTag(duplicateTag("g1")).assertDoesNotExist()
    }

    @Test
    fun `an open group says how many and why`() {
        show(withDuplicates(open = true))

        scrollTo("3 garments")

        // The count and the reasons, and the reasons are the ones that hold for
        // every member rather than for one pair inside it.
        compose.onNodeWithText("3 garments \u00b7 similar colour, same size").assertIsDisplayed()
    }

    @Test
    fun `every garment in a group is there to be tapped`() {
        show(withDuplicates(open = true))

        scrollTo("3 garments")

        for (id in listOf("g1", "g2", "g3")) {
            compose.onNodeWithTag(duplicateTag(id)).assertExists()
        }
    }

    @Test
    fun `tapping a photo opens that garment`() {
        // The only thing this section does about what it found: retiring and
        // deleting live on the garment screen, and this does not repeat them.
        show(withDuplicates(open = true))

        scrollTo("3 garments")
        compose.onNodeWithTag(duplicateTag("g2")).performClick()

        assertEquals("g2", opened)
    }

    @Test
    fun `a wardrobe with nothing alike says so rather than showing an empty heading`() {
        show(
            StatisticsViewModel.State(
                loading = false,
                view = view(),
                openSections = setOf(StatisticsSection.DUPLICATES),
                duplicates = emptyList(),
            ),
        )

        scrollTo("Nothing in your wardrobe")
        compose.onNodeWithText("Nothing in your wardrobe looks much like anything else.").assertIsDisplayed()
    }

}
