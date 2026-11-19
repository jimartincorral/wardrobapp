package com.wardrobapp.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.wardrobapp.presentation.BulkAddScreenState
import com.wardrobapp.presentation.BulkAddState
import com.wardrobapp.presentation.GarmentFormScreenState
import com.wardrobapp.presentation.GarmentFormState
import com.wardrobapp.ui.BULK_ADD_CROP
import com.wardrobapp.ui.BULK_ADD_PROGRESS
import com.wardrobapp.ui.BULK_ADD_SAVE
import com.wardrobapp.ui.BULK_ADD_SUMMARY
import com.wardrobapp.ui.BulkAddScreen
import com.wardrobapp.ui.GARMENT_FORM_LIST
import com.wardrobapp.ui.GarmentBatch
import com.wardrobapp.ui.GarmentFormScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The three faces of the bulk-add screen.
 *
 * Which one shows is decided by the queue, and the queue's own rules are tested
 * in :presentation. What is testable only here is that each state puts the right
 * thing in front of the reader -- an empty screen offering a way in rather than a
 * finished summary, a garment on screen being the whole garment form rather than
 * a cut-down one, and a drained queue saying what it came to.
 *
 * The garment face is drawn the way MainActivity draws it: the ordinary
 * GarmentFormScreen, through the screen's slot, with the queue around it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class BulkAddScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var saved = 0
    private var skipped = 0
    private var chosen = 0

    /** The start or the summary: nothing on screen to fill in. */
    private fun show(queue: BulkAddState) {
        compose.setContent {
            BulkAddScreen<Unit>(
                state = BulkAddScreenState(queue = queue),
                garment = null,
                onBack = {},
                onChoosePhotos = { chosen++ },
                onErrorDismissed = {},
            ) {}
        }
    }

    /** A garment on screen, the first of [photos], with the rest waiting behind it. */
    private fun showGarment(vararg photos: String, cutout: String = "") {
        val queue = BulkAddState().withPhotosAdded(photos.toList()).next()!!.second
        val form = GarmentFormState(imageUris = listOf(photos.first()), bgRemovedUris = listOf(cutout)).normalized()

        compose.setContent {
            BulkAddScreen(
                state = BulkAddScreenState(queue = queue),
                garment = Unit,
                onBack = {},
                onChoosePhotos = {},
                onErrorDismissed = {},
            ) {
                GarmentFormScreen(
                    state = GarmentFormScreenState(form = form),
                    isEditing = false,
                    brandSuggestions = { emptyList() },
                    onBack = {},
                    onAddPhoto = {},
                    onTakePhoto = {},
                    onPhotoSelected = {},
                    onPhotoRemoved = {},
                    onRemoveBackground = {},
                    onUndoBackground = {},
                    onCategorySelected = {},
                    onSubcategoryToggled = {},
                    onSeasonToggled = {},
                    onColorToggled = {},
                    onBrandChanged = {},
                    onSizeChanged = {},
                    onTagsChanged = {},
                    onMaterialToggled = {},
                    onAttributesChanged = {},
                    onSave = { saved++ },
                    onSaveAnyway = {},
                    onDuplicatesDismissed = {},
                    onErrorDismissed = {},
                    onImportUrlChanged = {},
                    onImportRequested = {},
                    onSharedLinkConfirmed = {},
                    onSharedLinkDismissed = {},
                    onImportProblemDismissed = {},
                    batch = GarmentBatch(queue = queue, onSkip = { skipped++ }),
                    onCrop = {},
                )
            }
        }
    }

    @Test
    fun `an untouched screen offers a way in`() {
        show(BulkAddState())

        compose.onNodeWithText("Choose photos").assertIsDisplayed()
        compose.onNodeWithTag(BULK_ADD_SUMMARY).assertDoesNotExist()
    }

    @Test
    fun `a garment on screen says which one it is`() {
        showGarment("a.jpg", "b.jpg", "c.jpg")

        compose.onNodeWithTag(BULK_ADD_PROGRESS).assertIsDisplayed()
        compose.onNodeWithText("1 of 3").assertIsDisplayed()
    }

    @Test
    fun `a garment on screen is the whole form, not a few fields of it`() {
        // What this screen used to be: a category, a type and a brand. Size,
        // material and tags are three of the fields it did not have; scrolled to,
        // because a LazyColumn has not composed what is below the fold.
        showGarment("a.jpg")

        for (section in listOf("Material", "Tags", "Size")) {
            compose.onNodeWithTag(GARMENT_FORM_LIST).performScrollToNode(hasText(section))
            compose.onNodeWithText(section).assertIsDisplayed()
        }
    }

    @Test
    fun `saving the garment on screen is reported`() {
        showGarment("a.jpg", "b.jpg")

        compose.onNodeWithTag(GARMENT_FORM_LIST).performScrollToNode(hasTestTag(BULK_ADD_SAVE))
        compose.onNodeWithTag(BULK_ADD_SAVE).performClick()

        assertEquals(1, saved)
    }

    @Test
    fun `a garment can be skipped rather than saved`() {
        showGarment("a.jpg", "b.jpg")

        compose.onNodeWithTag(GARMENT_FORM_LIST).performScrollToNode(hasText("Skip this photo"))
        compose.onNodeWithText("Skip this photo").performClick()

        assertEquals(1, skipped)
        assertEquals(0, saved)
    }

    @Test
    fun `a queued garment offers no import`() {
        // An import replaces the form's photos, and here that would throw away
        // the photo the queue opened the form on.
        showGarment("a.jpg")

        compose.onNodeWithText("Import from a link").assertDoesNotExist()
    }

    @Test
    fun `a queued photo can be cropped and cut out before it is saved`() {
        // The two things that decide how the garment looks in every list it will
        // appear in, and the queue's photos skipped the crop screen on the way in.
        showGarment("a.jpg")

        compose.onNodeWithTag(BULK_ADD_CROP).assertIsDisplayed()
        compose.onNodeWithText("Remove background").assertIsDisplayed()
    }

    @Test
    fun `once a background is gone the offer is to put it back`() {
        // Not both at once: two buttons where one applies is how somebody removes a
        // background twice.
        showGarment("a.jpg", cutout = "cut.png")

        compose.onNodeWithText("Undo background removal").assertIsDisplayed()
        compose.onNodeWithText("Remove background").assertDoesNotExist()
    }

    @Test
    fun `a drained queue says what it came to`() {
        show(
            BulkAddState()
                .withPhotosAdded(listOf("a.jpg", "b.jpg"))
                .advanced()
                .skipped()
        )

        compose.onNodeWithText("1 garment added.").assertIsDisplayed()
        compose.onNodeWithText("1 photo skipped.").assertIsDisplayed()
    }

    @Test
    fun `a batch with nothing skipped does not mention skipping`() {
        // "0 photos skipped" is a sentence about nothing having happened.
        show(BulkAddState().withPhotosAdded(listOf("a.jpg")).advanced())

        compose.onNodeWithText("1 garment added.").assertIsDisplayed()
        compose.onNodeWithText("0 photos skipped.").assertDoesNotExist()
    }

    @Test
    fun `a finished batch can take on more photos`() {
        show(BulkAddState().withPhotosAdded(listOf("a.jpg")).advanced())

        compose.onNodeWithText("Add more photos").performClick()

        assertEquals(1, chosen)
    }
}
