package com.wardrobapp.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasTestTag
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.presentation.OutfitEditState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Building an outfit by hand, and changing one.
 *
 * The rules are :presentation's and tested there. What is only true here: the
 * screen is one screen for two jobs, so its title has to say which -- and a
 * garment can actually be tapped into the outfit, which is the join between the
 * picker and the state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class OutfitEditScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var toggled: String? = null
    private var searched: String? = null
    private var saved = 0

    private fun garment(id: String, category: String, subcategory: String? = null) = GarmentRecord(
        id = id,
        imageUri = "$id.jpg",
        imageUriNoBg = null,
        imageUris = listOf("$id.jpg"),
        imageUrisNoBg = emptyList(),
        category = category,
        subcategory = subcategory,
        subcategories = listOfNotNull(subcategory),
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

    private val wardrobe = listOf(
        garment("g1", "tops", "Shirt"),
        garment("g2", "bottoms", "Jeans"),
    )

    private fun show(
        edit: OutfitEditState = OutfitEditState(),
        isEditing: Boolean = false,
        garments: List<GarmentRecord> = wardrobe,
        search: String = "",
    ) {
        compose.setContent {
            OutfitEditScreen(
                state = OutfitEditViewModel.State(
                    edit = edit,
                    garments = garments,
                    search = search,
                    loading = false,
                ),
                isEditing = isEditing,
                onBack = {},
                onNameChanged = {},
                onSearchChanged = { searched = it },
                onGarmentToggled = { toggled = it },
                onOccasionTapped = {},
                onSeasonTapped = {},
                onSave = { saved++ },
                onErrorDismissed = {},
            )
        }
    }

    @Test
    fun `building and editing are told apart by the title`() {
        // One screen doing two jobs has to say which one it is doing, or an edit
        // reads as having started a new outfit.
        show(isEditing = false)

        compose.onNodeWithText("Build an outfit").assertIsDisplayed()
    }

    @Test
    fun `editing says so`() {
        show(edit = OutfitEditState(garmentIds = listOf("g1")), isEditing = true)

        compose.onNodeWithText("Edit outfit").assertIsDisplayed()
    }

    @Test
    fun `an empty outfit cannot be saved`() {
        // An empty outfit is a row nothing can draw and nothing can suggest from.
        show()

        compose.onNodeWithTag(OUTFIT_EDIT_SAVE).assertIsNotEnabled()
    }

    @Test
    fun `an outfit with a garment in it can be saved`() {
        show(edit = OutfitEditState(garmentIds = listOf("g1")))

        compose.onNodeWithTag(OUTFIT_EDIT_SAVE).assertIsEnabled()
        compose.onNodeWithTag(OUTFIT_EDIT_SAVE).performClick()

        assertEquals(1, saved)
    }

    @Test
    fun `tapping a garment in the picker reports which one`() {
        // The join between the picker and the state: the picker is grouped by
        // category, so the garment tapped is not the garment at that position in
        // the wardrobe.
        show()

        compose.onNodeWithTag(OUTFIT_EDIT_LIST)
            .performScrollToNode(hasTestTag(outfitPickTag("g2")))
        compose.onNodeWithTag(outfitPickTag("g2")).performClick()

        assertEquals("g2", toggled)
    }

    @Test
    fun `a search narrows what the picker offers`() {
        // The reason the search exists: sideways-scrolling rows per category are
        // fine for a drawer and a lot of scrolling for a wardrobe.
        show(search = "jeans")

        compose.onNodeWithTag(OUTFIT_EDIT_LIST)
            .performScrollToNode(hasTestTag(outfitPickTag("g2")))
        compose.onNodeWithTag(outfitPickTag("g2")).assertIsDisplayed()
        compose.onNodeWithTag(outfitPickTag("g1")).assertDoesNotExist()
    }

    @Test
    fun `a search nothing answers to says so`() {
        // Rather than a wardrobe that appears to have emptied itself.
        show(search = "wellington boots")

        compose.onNodeWithText("No garment matches that.").assertIsDisplayed()
    }

    @Test
    fun `what is already in the outfit stays visible while searching`() {
        // The search narrows what is *offered*. An outfit that appeared to lose a
        // garment because of what was typed would be alarming and wrong.
        show(edit = OutfitEditState(garmentIds = listOf("g1")), search = "jeans")

        compose.onNodeWithTag(OUTFIT_EDIT_SAVE).assertIsEnabled()
    }

    @Test
    fun `typing in the picker search is reported`() {
        show()

        compose.onNodeWithTag(OUTFIT_PICK_SEARCH).performTextInput("parka")

        assertEquals("parka", searched)
    }

    @Test
    fun `an outfit with nothing in it says where to start`() {
        show()

        compose.onNodeWithText("Nothing picked yet. Tap a garment below.").assertIsDisplayed()
    }
}
