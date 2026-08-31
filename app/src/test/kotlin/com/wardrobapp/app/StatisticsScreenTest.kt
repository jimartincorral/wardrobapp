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
import com.wardrobapp.domain.PhantomGarment
import androidx.compose.ui.test.onNodeWithContentDescription
import com.wardrobapp.data.GapOutfit
import com.wardrobapp.data.GapWithPhotos
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.domain.GapEvidence
import com.wardrobapp.domain.MIN_WARDROBE_FOR_GAPS
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.OutfitSlot
import com.wardrobapp.domain.Season
import com.wardrobapp.domain.WardrobeGap
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
    private var wanted: PhantomGarment? = null

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
                onGapAddRequested = { wanted = it },
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
            DuplicateGarmentGroup(listOf(garment("g1"), garment("g2"), garment("g3"))),
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
    fun `an open group says how many, and nothing about why`() {
        show(withDuplicates(open = true))

        scrollTo("3 garments")

        // The count alone. Every group is here for the identical reason -- the
        // same kind of thing in the same colours -- so a reason line would repeat
        // the section's own heading once per row.
        compose.onNodeWithText("3 garments").assertIsDisplayed()
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

    @Test
    fun `a section that has not looked yet does not claim the wardrobe is clean`() {
        // The sweep is asked for when the section opens, so there is a moment with
        // no answer. Saying "nothing looks like anything else" then would be a
        // verdict delivered before anything had been examined.
        show(
            StatisticsViewModel.State(
                loading = false,
                view = view(),
                openSections = setOf(StatisticsSection.DUPLICATES),
                duplicates = null,
            ),
        )

        scrollTo("Things you may own twice")
        compose.onNodeWithText("Nothing in your wardrobe looks much like anything else.")
            .assertDoesNotExist()
    }

    // ---- What you are missing ----------------------------------------------

    /** A garment record, for the photographs an example outfit is made of. */
    private fun record(id: String, category: String, subcategory: String) =
        normalizeGarmentRow(
            mapOf(
                "id" to id,
                "image_uri" to "$id.jpg",
                "image_uris" to "[]",
                "category" to category,
                "subcategory" to subcategory,
                "subcategories" to "[\"$subcategory\"]",
                "tags" to "[]",
                "color_primary" to "#000000",
                "color_palette" to "[]",
                "is_available" to 1L,
                "created_at" to "2026-01-01",
                "updated_at" to "2026-01-01",
            ),
            "file:///photos/",
        )

    private fun gap(
        evidence: GapEvidence = GapEvidence.NOTHING_FITS,
        want: PhantomGarment = PhantomGarment("shoes", "Loafers", "#000000"),
        unlocked: Long = 36,
        alternatives: List<PhantomGarment> = emptyList(),
        replaces: GarmentRecord? = null,
        examples: List<GapOutfit> = listOf(
            GapOutfit(
                name = "Loafers and a shirt",
                // The hole is a null, exactly as the data layer hands it over.
                garments = listOf(null, record("top-1", "tops", "Shirt")),
            )
        ),
    ) = GapWithPhotos(
        gap = WardrobeGap(
            want = want,
            slot = OutfitSlot.SHOES,
            occasion = Occasion.WORK,
            season = Season.FALL,
            outfitsUnlocked = unlocked,
            examples = emptyList(),
            evidence = evidence,
            replaces = null,
            alternatives = alternatives,
        ),
        examples = examples,
        replaces = replaces,
    )

    private fun showingGaps(
        gaps: List<GapWithPhotos>?,
        inUse: Long = 20,
    ) = StatisticsViewModel.State(
        loading = false,
        view = view(inUse = inUse),
        openSections = setOf(StatisticsSection.GAPS),
        gaps = gaps,
    )

    @Test
    fun `the gaps section is shut until it is asked for`() {
        show()

        scrollTo("What you are missing")
        compose.onNodeWithText("What you are missing").performClick()

        assertEquals(StatisticsSection.GAPS, tapped)
    }

    @Test
    fun `a gap says what is missing and how much it would unlock`() {
        show(showingGaps(listOf(gap())))

        scrollTo("Loafers")
        compose.onNodeWithText("Black Loafers").assertIsDisplayed()
        compose.onNodeWithText("36 outfits you cannot make today").assertIsDisplayed()
        compose.onNodeWithText("Work: nothing you own is dressed for it.").assertIsDisplayed()
    }

    /**
     * The card's whole argument is an outfit with a hole in it, and the hole has to
     * be described: a screen reader given four undescribed images and a sentence
     * would be told everything except the one thing being said.
     */
    @Test
    fun `the garment that is missing is described where it would go`() {
        show(showingGaps(listOf(gap())))

        scrollTo("Loafers")
        compose.onNodeWithContentDescription("Where Black Loafers would go").assertIsDisplayed()
    }

    @Test
    fun `tapping add asks for the garment the card was about`() {
        val want = PhantomGarment("shoes", "Loafers", "#000000")
        show(showingGaps(listOf(gap(want = want))))

        scrollTo("Loafers")
        compose.onNodeWithText("Add it").performClick()

        assertEquals(want, wanted)
    }

    @Test
    fun `a retired garment nothing replaced is named rather than described`() {
        show(
            showingGaps(
                listOf(
                    gap(
                        evidence = GapEvidence.RETIRED_UNREPLACED,
                        replaces = record("gone", "shoes", "Boots"),
                    )
                )
            )
        )

        scrollTo("Loafers")
        compose.onNodeWithText("You retired your Boots and nothing replaced it.").assertIsDisplayed()
    }

    /**
     * Ties are the normal case in a wardrobe with nothing rated, and naming one
     * type as the answer would be showing the order of the catalogue as advice.
     */
    @Test
    fun `types that would do just as well are offered`() {
        show(
            showingGaps(
                listOf(
                    gap(
                        alternatives = listOf(
                            PhantomGarment("shoes", "Flats", "#000000"),
                            PhantomGarment("shoes", "Heels", "#000000"),
                        )
                    )
                )
            )
        )

        scrollTo("Loafers")
        compose.onNodeWithText("Or: Flats, Heels").assertIsDisplayed()
    }

    @Test
    fun `no alternatives line when one candidate genuinely won`() {
        show(showingGaps(listOf(gap(alternatives = emptyList()))))

        scrollTo("Loafers")
        compose.onNodeWithText("Or:", substring = true).assertDoesNotExist()
    }

    /**
     * The two silences, told apart. A wardrobe too small to reason about gets no
     * advice by design, and telling its owner that nothing is missing would be the
     * app declining to answer while sounding like it had answered.
     */
    @Test
    fun `a wardrobe too small to reason about is not told it is complete`() {
        show(showingGaps(gaps = emptyList(), inUse = MIN_WARDROBE_FOR_GAPS - 1L))

        scrollTo("Not enough to go on")
        compose.onNodeWithText("Nothing obvious is missing.", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a wardrobe with nothing missing is told so`() {
        show(showingGaps(gaps = emptyList(), inUse = 40))

        scrollTo("Nothing obvious is missing")
        compose.onNodeWithText("Nothing obvious is missing.", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a section that has not looked yet does not claim the wardrobe is complete`() {
        // The analysis is asked for when the section opens, so there is a moment
        // with no answer. It is the slowest thing the app computes, which makes
        // this moment long enough to see -- and "nothing is missing" shown in it
        // would be a verdict delivered before anything had been looked at.
        show(showingGaps(gaps = null))

        scrollTo("What you are missing")
        compose.onNodeWithText("Nothing obvious is missing.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Not enough to go on", substring = true).assertDoesNotExist()
    }
}
