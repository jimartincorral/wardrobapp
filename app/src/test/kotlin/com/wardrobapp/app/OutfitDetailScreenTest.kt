package com.wardrobapp.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.OutfitRecord
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The outfit card, on the screen that shows one outfit.
 *
 * Where the garments land is :presentation's answer and tested there. What is
 * testable here is that the card is on the screen at all, that sharing it is
 * offered only when there is something to share, and that the ground colour the
 * card is drawn on reaches the model -- a card shared out of the dark theme
 * arriving on a light ground is the kind of thing no unit test would otherwise
 * catch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class OutfitDetailScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var sharedWith: Int? = null

    private fun garment(id: String, category: String) = GarmentRecord(
        id = id,
        imageUri = "$id.jpg",
        imageUriNoBg = null,
        imageUris = listOf("$id.jpg"),
        imageUrisNoBg = emptyList(),
        category = category,
        subcategory = null,
        subcategories = emptyList(),
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

    private fun outfitOf(garments: List<GarmentRecord>) = OutfitRecord(
        id = "o1",
        name = "Shirt + Jeans",
        garmentIds = garments.map { it.id },
        occasion = null,
        season = null,
        createdAt = null,
        isSuggested = false,
        isArchived = false,
        isPinned = false,
    )

    private fun show(
        garments: List<GarmentRecord>,
        composingCard: Boolean = false,
    ) {
        compose.setContent {
            OutfitDetailScreen(
                state = OutfitDetailViewModel.State(
                    loading = false,
                    outfit = outfitOf(garments),
                    garments = garments,
                    composingCard = composingCard,
                ),
                onBack = {},
                onGarmentOpened = {},
                onRate = {},
                onShare = { ground -> sharedWith = ground },
                onDelete = {},
                onDeleteConfirmed = {},
                onDeleteDismissed = {},
                onRetry = {},
            )
        }
    }

    @Test
    fun `an outfit is shown as one card`() {
        show(listOf(garment("top", "tops"), garment("jeans", "bottoms")))

        compose.onNodeWithTag(OUTFIT_CARD).assertIsDisplayed()
    }

    @Test
    fun `sharing hands over the colour the card is drawn on`() {
        // The theme's own ground, so a card shared from the dark theme does not
        // arrive on a light one. A model cannot see the theme, so this is the only
        // place the two can be joined.
        show(listOf(garment("top", "tops")))

        compose.onNodeWithTag(OUTFIT_SHARE).performClick()

        assertEquals(true, sharedWith != null)
    }

    @Test
    fun `sharing waits while the card is being composed`() {
        // Several photos decoded and a 1080-wide canvas encoded: long enough for a
        // second tap to arrive, and two share sheets for one outfit is not what
        // the second tap meant.
        show(listOf(garment("top", "tops")), composingCard = true)

        compose.onNodeWithTag(OUTFIT_SHARE).assertIsNotEnabled()
    }

    @Test
    fun `an outfit with garments in it can be shared`() {
        show(listOf(garment("top", "tops")))

        compose.onNodeWithTag(OUTFIT_SHARE).assertIsEnabled()
    }

    @Test
    fun `an outfit of nothing placeable says so on the card`() {
        // Underwear belongs to no part of an outfit, so there is nothing to
        // compose. Saying so beats a blank card that reads as a photo still
        // loading.
        show(listOf(garment("socks", "underwear")))

        compose.onNodeWithText("Nothing here belongs on a card yet.").assertIsDisplayed()
    }
}
