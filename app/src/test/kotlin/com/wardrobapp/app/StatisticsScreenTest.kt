package com.wardrobapp.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.wardrobapp.presentation.BrandSort
import com.wardrobapp.presentation.Distribution
import com.wardrobapp.presentation.LifespanEntry
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

    private fun show(
        state: StatisticsViewModel.State = StatisticsViewModel.State(loading = false, view = view()),
    ) {
        compose.setContent {
            StatisticsScreen(
                state = state,
                onCategoryTapped = {},
                onBrandSortChanged = {},
                onSectionTapped = { tapped = it },
                onRetry = {},
            )
        }
    }

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
        compose.onNodeWithText("By brand").assertIsDisplayed()
        compose.onNodeWithText("How long things lasted").assertIsDisplayed()
        compose.onNodeWithText("Uniqlo").assertDoesNotExist()
    }

    @Test
    fun `tapping a heading asks for that section`() {
        show()

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
        compose.onNodeWithText("Uniqlo").assertIsDisplayed()
        compose.onNodeWithText("Arket").assertIsDisplayed()
        // And the sort chips travel with the section rather than sitting beside a
        // heading that is now a button.
        compose.onNodeWithText("A-Z").assertIsDisplayed()
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
}
