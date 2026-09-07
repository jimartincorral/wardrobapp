package com.wardrobapp.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.wardrobapp.presentation.LanguageChoice
import com.wardrobapp.presentation.MAX_RATING
import com.wardrobapp.presentation.OnboardingStep
import com.wardrobapp.presentation.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The three screens before Home.
 *
 * What is worth testing here is the wiring, not the words: this is the only place
 * in the app with three buttons that all move forward and one that leaves, and
 * every one of them looks right whichever callback it is attached to. A "Not now"
 * that advanced instead of skipping would pass any screenshot.
 *
 * The tall qualifier is deliberate. Each step holds its button at the bottom with
 * a weighted spacer inside a scrollable column, and on a short screen the button
 * is real but off-screen -- which a click on an unscrolled node cannot reach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class OnboardingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var continued = 0
    private var skipped = 0
    private var restores = 0
    private val themes = mutableListOf<ThemeChoice>()
    private val languages = mutableListOf<LanguageChoice>()

    private fun show(
        step: OnboardingStep,
        theme: ThemeChoice = ThemeChoice.SYSTEM,
        language: LanguageChoice = LanguageChoice.SYSTEM,
    ) {
        compose.setContent {
            OnboardingScreen(
                step = step,
                theme = theme,
                onThemeSelected = { themes += it },
                language = language,
                onLanguageSelected = { languages += it },
                onContinue = { continued++ },
                onSkip = { skipped++ },
                onRestoreRequested = { restores++ },
            )
        }
    }

    @Test
    fun `the welcome screen offers three ways on, and they are three different ways`() {
        show(OnboardingStep.WELCOME)

        compose.onNodeWithText("Start fresh").performClick()
        assertEquals(1, continued)
        assertEquals(0, skipped)
        assertEquals(0, restores)

        // Straight to the picker. There is no screen of ours in between, so
        // nothing here should count as having agreed to anything else.
        compose.onNodeWithText("Restore a backup").performClick()
        assertEquals(1, restores)
        assertEquals(1, continued)

        // Skipping is not advancing: it marks the flow seen and leaves.
        compose.onNodeWithText("Not now").performClick()
        assertEquals(1, skipped)
        assertEquals(1, continued)
    }

    @Test
    fun `both chip rows are the real settings, and each reports its own choice`() {
        show(OnboardingStep.WELCOME)

        // "Automatic" is the first option of both rows, which is why the specific
        // chips are the ones tapped: a lookup by that text matches two nodes.
        compose.onAllNodesWithText("Automatic").assertCountEquals(2)

        compose.onNodeWithText("Dark").performClick()
        compose.onNodeWithText("Español").performClick()

        assertEquals(listOf(ThemeChoice.DARK), themes)
        assertEquals(listOf(LanguageChoice.SPANISH), languages)
    }

    @Test
    fun `the settings hints stay on the settings screen`() {
        show(OnboardingStep.WELCOME)

        // The headings are shared with Settings on purpose; the explanations under
        // them are not. This screen is asking a question, not documenting an
        // option, and the hint about Android remembering the language is about a
        // settings screen.
        compose.onNodeWithText("Theme").assertIsDisplayed()
        compose.onNodeWithText("Language").assertIsDisplayed()
        compose.onAllNodesWithText(
            "Automatic follows whether the device is set to light or dark."
        ).assertCountEquals(0)
    }

    @Test
    fun `the second screen explains a photo and moves on`() {
        show(OnboardingStep.ADDING)

        compose.onNodeWithText("A photo, from the camera or the gallery").assertIsDisplayed()
        compose.onNodeWithText("The colours fill themselves in").assertIsDisplayed()
        compose.onNodeWithText("You pick the category").assertIsDisplayed()

        compose.onNodeWithText("Next").performClick()
        assertEquals(1, continued)

        // No second way past it. Skipping is the welcome screen's alone.
        assertEquals(0, skipped)
    }

    @Test
    fun `the third screen shows the card that will ask, and does not ask`() {
        show(OnboardingStep.LEARNING)

        compose.onNodeWithText("Denim jacket, white tee, chinos").assertIsDisplayed()
        compose.onNodeWithText("How was it?").assertIsDisplayed()

        // The stars are a picture of a control rather than one: `Stars` names each
        // of its targets after the rating it gives, and none of those exist here.
        // A tap on them would be a rating of an outfit that does not exist.
        for (star in 1..MAX_RATING) {
            compose.onAllNodesWithTag(starTag(star)).assertCountEquals(0)
        }

        compose.onNodeWithText("Got it").performClick()
        assertEquals(1, continued)
    }
}
