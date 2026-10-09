package com.wardrobapp.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.SuggestedOutfit
import com.wardrobapp.presentation.OutfitsScreenState
import com.wardrobapp.presentation.TasteTrainingScreenState
import com.wardrobapp.ui.TASTE_TRAINING_PROGRESS
import com.wardrobapp.ui.TASTE_TRAINING_SKIP
import com.wardrobapp.ui.TASTE_TRAINING_SUMMARY
import com.wardrobapp.ui.TasteTrainingScreen
import com.wardrobapp.ui.starTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The training screen: a round in progress, and the faces at either end of
 * one. What a rating does to the engine is the screen model's business, in
 * :presentation; this is whether the taps reach it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class TasteTrainingScreenTest {

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

    private fun suggestion(id: String) = OutfitsScreenState.Suggestion(
        id = id,
        outfit = SuggestedOutfit(
            name = "Shirt + Jeans",
            score = 0.8,
            garments = listOf(garment("g1")),
            reasons = emptyList(),
        ),
    )

    private var rated: Int? = null
    private var skips = 0
    private var keptGoing = 0
    private var done = 0

    private fun show(state: TasteTrainingScreenState) {
        compose.setContent {
            TasteTrainingScreen(
                state = state,
                onBack = {},
                onRate = { rated = it },
                onSkip = { skips++ },
                onSave = {},
                onKeepGoing = { keptGoing++ },
                onDone = { done++ },
                onRetry = {},
                onGarmentOpened = {},
            )
        }
    }

    private fun inProgress(position: Int = 0) = TasteTrainingScreenState(
        round = (1..10).map { suggestion("s$it") },
        position = position,
        hasFetched = true,
    )

    @Test
    fun `a round in progress says where it is, and the stars report the rating`() {
        show(inProgress(position = 2))

        compose.onNodeWithTag(TASTE_TRAINING_PROGRESS).assertIsDisplayed()
        compose.onNodeWithText("3 of 10").assertIsDisplayed()

        compose.onNodeWithTag(starTag(4)).performClick()
        assertEquals(4, rated)
    }

    @Test
    fun `skipping reports a skip`() {
        show(inProgress())

        compose.onNodeWithTag(TASTE_TRAINING_SKIP).performClick()
        assertEquals(1, skips)
    }

    @Test
    fun `a finished round offers another, or to stop`() {
        show(inProgress(position = 10).copy(rated = 7, liked = 5, disliked = 1, skipped = 3))

        compose.onNodeWithTag(TASTE_TRAINING_SUMMARY).assertIsDisplayed()
        compose.onNodeWithText("You rated 7 outfits.").assertIsDisplayed()
        compose.onNodeWithText("5 liked · 1 not for you").assertIsDisplayed()
        compose.onNodeWithText("3 skipped").assertIsDisplayed()

        compose.onNodeWithText("Keep going").performClick()
        assertEquals(1, keptGoing)
        compose.onNodeWithText("Done").performClick()
        assertEquals(1, done)
    }

    @Test
    fun `a spent wardrobe is told so, and not offered another round`() {
        show(inProgress(position = 10).copy(rated = 10, exhausted = true))

        compose.onNodeWithText("Keep going").assertDoesNotExist()
        compose.onNodeWithText("every outfit the app can put together", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun `a wardrobe nothing can be built from says so`() {
        show(TasteTrainingScreenState(hasFetched = true))

        compose.onNodeWithText("No outfits could be built", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(TASTE_TRAINING_SKIP).assertDoesNotExist()
    }
}
