package com.wardrobapp.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.wardrobapp.presentation.FirstStep
import com.wardrobapp.presentation.FirstSteps
import com.wardrobapp.presentation.firstStepsFor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The First steps card, on the screen it lives on.
 *
 * Tested through [HomeScreen] rather than on its own, because half of what is
 * worth checking is that it is the *first* thing on Home and that it can be absent
 * without taking the rest of the screen with it.
 *
 * Which rows are ticked is [com.wardrobapp.presentation.FirstStepsTest]'s
 * business. What is here is the wiring: three rows that all look alike and lead
 * three different places, which is the shape where a mix-up is invisible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class FirstStepsCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<FirstStep>()
    private var dismissed = 0
    private var adds = 0
    private var outfits = 0

    private fun show(steps: FirstSteps?) {
        compose.setContent {
            HomeScreen(
                state = HomeViewModel.State(loading = false, items = 0, archived = 0),
                firstSteps = steps,
                onFirstStepsDismissed = { dismissed++ },
                onFirstStep = { opened += it },
                onAddRequested = { adds++ },
                onWardrobeRequested = {},
                onArchivedRequested = {},
                onOutfitsRequested = { outfits++ },
                onStatisticsRequested = {},
                onSettingsRequested = {},
                onRetry = {},
            )
        }
    }

    private fun fresh() = firstStepsFor(
        dismissed = false,
        garments = 0,
        bulkAddUsed = false,
        ratedOutfits = 0,
    )

    @Test
    fun `each row leads to its own job`() {
        show(fresh())

        compose.onNodeWithText("Add your first garment").performClick()
        compose.onNodeWithText("Add several at once").performClick()
        compose.onNodeWithText("Rate an outfit idea").performClick()

        // In the order they were tapped, and each exactly once: three rows that
        // reported the same step would pass a check that only counted taps.
        assertEquals(
            listOf(FirstStep.GARMENT, FirstStep.BULK_ADD, FirstStep.RATE),
            opened,
        )
    }

    @Test
    fun `a row is not the buttons below it`() {
        show(fresh())

        compose.onNodeWithText("Add your first garment").performClick()

        // The card's first row and Home's own "Add a garment" button end up in the
        // same place, and they are not the same control: the row goes through the
        // card's callback, which is what lets the row be a checklist item rather
        // than a second copy of the button.
        assertEquals(listOf(FirstStep.GARMENT), opened)
        assertEquals(0, adds)
        assertEquals(0, outfits)
    }

    @Test
    fun `dismiss is its own answer`() {
        show(fresh())

        compose.onNodeWithText("Dismiss").performClick()

        assertEquals(1, dismissed)
        assertEquals(emptyList<FirstStep>(), opened)
    }

    @Test
    fun `no card leaves the rest of Home alone`() {
        show(null)

        compose.onAllNodesWithTag(FIRST_STEPS_CARD).assertCountEquals(0)

        // Everything the card was sitting on top of is still there.
        compose.onNodeWithText("Keep track of everything you wear.").assertIsDisplayed()
        compose.onNodeWithText("Add a garment").assertIsDisplayed()
        compose.onNodeWithText("Outfit ideas").assertIsDisplayed()
    }

    @Test
    fun `the card is there when there is work in it`() {
        show(fresh())

        compose.onAllNodesWithTag(FIRST_STEPS_CARD).assertCountEquals(1)
        compose.onNodeWithText("First steps").assertIsDisplayed()
    }
}
