package com.wardrobapp.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.SuggestedOutfit
import com.wardrobapp.domain.OutfitReason
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the outfits screen says about a suggestion.
 *
 * The scoring is :domain's and tested there. What is testable here is the part a
 * person meets: that the reasons the engine gave are the words on the card, and
 * that rating one leads to the prompt rather than silently filing it away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class OutfitsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun garment(id: String) = GarmentRecord(
        id = id,
        imageUri = "$id.jpg",
        imageUriNoBg = null,
        imageUris = listOf("$id.jpg"),
        imageUrisNoBg = emptyList(),
        category = "tops",
        subcategory = "Shirt",
        subcategories = listOf("Shirt"),
        tags = emptyList(),
        brand = null,
        colorPrimary = "#000000",
        colorSecondary = null,
        colorPalette = listOf("#000000"),
        size = null,
        purchaseDate = null,
        isAvailable = true,
        unavailableDate = null,
        createdAt = null,
        updatedAt = null,
    )

    private fun suggestion(reasons: List<OutfitReason>) = OutfitsViewModel.Suggestion(
        id = "s1",
        outfit = SuggestedOutfit(
            name = "Shirt + Jeans",
            score = 0.8,
            garments = listOf(garment("g1")),
            reasons = reasons,
        ),
    )

    private var rated: Int? = null

    private fun show(state: OutfitsViewModel.State) {
        compose.setContent {
            OutfitsScreen(
                state = state,
                onSeasonTapped = {},
                onOccasionTapped = {},
                onGenerate = {},
                onSeedCleared = {},
                onKeep = {},
                onKeepDismissed = {},
                onArchivedToggled = {},
                onSave = {},
                onRate = { _, stars -> rated = stars },
                onPinToggled = {},
                onDeleteRequested = {},
                onDeleteConfirmed = {},
                onDeleteDismissed = {},
                onGarmentOpened = {},
                onOutfitOpened = {},
                onBuildRequested = {},
            )
        }
    }

    @Test
    fun `a star says which rating it gives, not which glyph it is`() {
        // The stars were two text characters until the design replaced them with
        // icons, and a screen reader read the character: five controls that
        // announced "white star" and gave five different ratings. This is the part
        // of that swap worth holding on to.
        show(OutfitsViewModel.State(suggestions = listOf(suggestion(emptyList()))))

        compose.onNodeWithContentDescription("Rate 1 star").assertIsDisplayed()
        compose.onNodeWithContentDescription("Rate 4 stars").assertIsDisplayed()
    }

    @Test
    fun `before anything has been asked for, the button offers to suggest`() {
        show(OutfitsViewModel.State(hasGenerated = false))

        compose.onNodeWithText("Suggest outfits").assertIsDisplayed()
    }

    @Test
    fun `once it has been asked, the button offers to ask again`() {
        // Same button, different job: the first press fills an empty list, and
        // every one after it replaces three outfits that have already been read.
        // Two tests rather than one, because the rule composes once.
        show(OutfitsViewModel.State(hasGenerated = true))

        compose.onNodeWithText("Suggest again").assertIsDisplayed()
        compose.onNodeWithText("Suggest outfits").assertDoesNotExist()
    }

    @Test
    fun `an arriving suggestion is on screen rather than animating from nowhere`() {
        // The cards land one at a time, which is a transform over content that is
        // already composed. Worth a test because the other way of writing it --
        // withholding each card until its turn -- looks identical on a phone and
        // makes the card unreachable to anything that does not wait out an
        // animation, this suite included.
        show(OutfitsViewModel.State(suggestions = listOf(suggestion(emptyList()))))

        compose.onNodeWithText("Shirt + Jeans").assertIsDisplayed()
    }

    @Test
    fun `a suggestion says why it came up`() {
        // A score of 0.81 tells nobody anything. These are the parts of that number
        // worth reading, and the reason the engine keeps them rather than summing
        // them away.
        show(
            OutfitsViewModel.State(
                hasGenerated = true,
                suggestions = listOf(suggestion(listOf(OutfitReason.LEARNED, OutfitReason.COLOURS))),
            )
        )

        compose.onNodeWithTag(OUTFIT_REASONS).assertIsDisplayed()
        compose.onNodeWithText("You rated these together · The colours work").assertIsDisplayed()
    }

    @Test
    fun `a suggestion with nothing to say about it says nothing`() {
        // Rather than an empty line, or a placeholder claiming a reason it does
        // not have.
        show(
            OutfitsViewModel.State(
                hasGenerated = true,
                suggestions = listOf(suggestion(emptyList())),
            )
        )

        compose.onNodeWithTag(OUTFIT_REASONS).assertDoesNotExist()
    }

    @Test
    fun `rating a suggestion reports the stars that were tapped`() {
        show(
            OutfitsViewModel.State(
                hasGenerated = true,
                suggestions = listOf(suggestion(emptyList())),
            )
        )

        compose.onNodeWithTag(starTag(4)).performClick()

        assertEquals(4, rated)
    }

    @Test
    fun `the prompt after a rating offers to keep the outfit`() {
        // Rating archives; this is where keeping it is offered. Both answers leave
        // the rating alone, so there is nothing destructive to guard here -- what
        // matters is that the prompt exists at all, since without it a rating
        // would quietly file the outfit away with no way to change your mind.
        show(
            OutfitsViewModel.State(
                hasGenerated = true,
                suggestions = listOf(suggestion(emptyList())),
                keeping = suggestion(emptyList()).copy(rating = 2),
            )
        )

        compose.onNodeWithText("Keep this outfit?").assertIsDisplayed()
        compose.onNodeWithText("Keep it").assertIsDisplayed()
        compose.onNodeWithText("Just learn from it").assertIsDisplayed()
    }

    // The archive is not a feature until there is something in it, so the toggle
    // that reveals it stays away until then. Two tests rather than one because a
    // composition can only be set once per test, and the empty and non-empty
    // cases are two compositions.

    @Test
    fun `an empty archive offers no way in`() {
        show(OutfitsViewModel.State(hasGenerated = true, archivedCount = 0))

        compose.onNodeWithTag(OUTFIT_ARCHIVE_TOGGLE).assertDoesNotExist()
    }

    @Test
    fun `an archive with outfits in it says how many`() {
        show(OutfitsViewModel.State(hasGenerated = true, archivedCount = 3))

        compose.onNodeWithText("Show 3 rated-only outfits").assertIsDisplayed()
    }
}
