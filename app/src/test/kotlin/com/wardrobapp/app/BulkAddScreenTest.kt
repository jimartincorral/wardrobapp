package com.wardrobapp.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.wardrobapp.presentation.BulkAddState
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
 * finished summary, and a drained queue saying what it came to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class BulkAddScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var saved = 0
    private var chosen = 0

    private fun show(queue: BulkAddState) {
        compose.setContent {
            BulkAddScreen(
                state = BulkAddViewModel.State(queue = queue),
                onBack = {},
                onChoosePhotos = { chosen++ },
                onCategorySelected = {},
                onSubcategoryToggled = {},
                onBrandChanged = {},
                onCrop = {},
                onRemoveBackground = {},
                onUndoBackground = {},
                onSave = { saved++ },
                onSkip = {},
                onErrorDismissed = {},
            )
        }
    }

    @Test
    fun `an untouched screen offers a way in`() {
        show(BulkAddState())

        compose.onNodeWithText("Choose photos").assertIsDisplayed()
        compose.onNodeWithTag(BULK_ADD_SUMMARY).assertDoesNotExist()
    }

    @Test
    fun `a queued photo says which one it is`() {
        show(BulkAddState().withDraftsAdded(listOf("a.jpg", "b.jpg", "c.jpg")))

        compose.onNodeWithTag(BULK_ADD_PROGRESS).assertIsDisplayed()
        compose.onNodeWithText("1 of 3").assertIsDisplayed()
    }

    @Test
    fun `saving the garment on screen is reported`() {
        show(BulkAddState().withDraftsAdded(listOf("a.jpg")))

        compose.onNodeWithTag(BULK_ADD_SAVE).performClick()

        assertEquals(1, saved)
    }

    @Test
    fun `a queued photo can be cropped and cut out before it is saved`() {
        // The two things that decide how the garment looks in every list it will
        // appear in. Offered here rather than left to an edit nobody comes back to
        // make.
        show(BulkAddState().withDraftsAdded(listOf("a.jpg")))

        compose.onNodeWithTag(BULK_ADD_CROP).assertIsDisplayed()
        compose.onNodeWithText("Remove background").assertIsDisplayed()
    }

    @Test
    fun `once a background is gone the offer is to put it back`() {
        // Not both at once: two buttons where one applies is how somebody removes a
        // background twice.
        show(BulkAddState().withDraftsAdded(listOf("a.jpg")).withCutout("a.jpg", "cut.png"))

        compose.onNodeWithText("Undo background removal").assertIsDisplayed()
        compose.onNodeWithText("Remove background").assertDoesNotExist()
    }

    @Test
    fun `a drained queue says what it came to`() {
        show(
            BulkAddState()
                .withDraftsAdded(listOf("a.jpg", "b.jpg"))
                .advanced()
                .skipped()
        )

        compose.onNodeWithText("1 garment added.").assertIsDisplayed()
        compose.onNodeWithText("1 photo skipped.").assertIsDisplayed()
    }

    @Test
    fun `a batch with nothing skipped does not mention skipping`() {
        // "0 photos skipped" is a sentence about nothing having happened.
        show(BulkAddState().withDraftsAdded(listOf("a.jpg")).advanced())

        compose.onNodeWithText("1 garment added.").assertIsDisplayed()
        compose.onNodeWithText("0 photos skipped.").assertDoesNotExist()
    }

    @Test
    fun `a finished batch can take on more photos`() {
        show(BulkAddState().withDraftsAdded(listOf("a.jpg")).advanced())

        compose.onNodeWithText("Add more photos").performClick()

        assertEquals(1, chosen)
    }
}
