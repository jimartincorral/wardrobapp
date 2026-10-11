package com.wardrobapp.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.wardrobapp.presentation.InspirationScreenState
import com.wardrobapp.presentation.StyleModelState
import com.wardrobapp.presentation.StyleModelState.Status
import com.wardrobapp.ui.INSPIRATION_MODEL_NOTICE
import com.wardrobapp.ui.InspirationScreen
import com.wardrobapp.ui.STYLE_MODEL_CANCEL
import com.wardrobapp.ui.STYLE_MODEL_DOWNLOAD
import com.wardrobapp.ui.STYLE_MODEL_PROGRESS
import com.wardrobapp.ui.STYLE_MODEL_REMOVE
import com.wardrobapp.ui.StyleModelCard
import com.wardrobapp.ui.StyleModelLooksNotice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The style model's card says what the download costs before anybody taps,
 * and the looks screen says when saved looks do nothing yet. Which state is
 * which is StyleModelState's business, in :presentation; this is what each
 * state puts on the screen, and whether the taps reach their handlers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class StyleModelCardTest {

    @get:Rule
    val compose = createComposeRule()

    private var downloads = 0
    private var cancels = 0
    private var removals = 0

    private fun card(status: Status, paired: Boolean = false, metered: Boolean = false) {
        compose.setContent {
            StyleModelCard(
                state = StyleModelState(status, bytes = 89_313_414, paired = paired, metered = metered),
                onDownload = { downloads++ },
                onCancel = { cancels++ },
                onRemove = { removals++ },
            )
        }
    }

    @Test
    fun `the download button says its size, and so does the explanation`() {
        card(Status.Absent, metered = true)

        compose.onNodeWithText("Download (85.2 MB)").assertIsDisplayed()
        compose.onNodeWithText("style model of 85.2 MB", substring = true).assertIsDisplayed()
        compose.onNodeWithText("You're on mobile data.").assertIsDisplayed()
        compose.onNodeWithTag(STYLE_MODEL_DOWNLOAD).performClick()
        assertEquals(1, downloads)
    }

    @Test
    fun `a paired phone is told Home Assistant does this already`() {
        card(Status.Absent, paired = true)

        compose.onNodeWithText("Home Assistant already reads your photos", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a download in progress shows its progress and can be cancelled`() {
        card(Status.Downloading(0.4f))

        compose.onNodeWithTag(STYLE_MODEL_PROGRESS).assertIsDisplayed()
        compose.onNodeWithTag(STYLE_MODEL_CANCEL).performClick()
        assertEquals(1, cancels)
    }

    @Test
    fun `a downloaded model can be removed`() {
        card(Status.Ready)
        compose.onNodeWithTag(STYLE_MODEL_REMOVE).performClick()
        assertEquals(1, removals)
    }

    @Test
    fun `a failed download says why and offers to try again`() {
        card(Status.Failed("The connection dropped."))

        compose.onNodeWithText("The download stopped: The connection dropped.").assertIsDisplayed()
        compose.onNodeWithTag(STYLE_MODEL_DOWNLOAD).performClick()
        assertEquals(1, downloads)
    }

    @Test
    fun `the looks screen says outright that looks do nothing until the model is here`() {
        compose.setContent {
            InspirationScreen(
                state = InspirationScreenState(loading = false),
                onBack = {},
                onAddRequested = {},
                onRemove = {},
                onRetry = {},
                modelNotice = {
                    StyleModelLooksNotice(
                        state = StyleModelState(Status.Absent, bytes = 89_313_414),
                        onDownload = { downloads++ },
                        onCancel = {},
                        onRemove = {},
                    )
                },
            )
        }

        compose.onNodeWithTag(INSPIRATION_MODEL_NOTICE).assertIsDisplayed()
        compose.onNodeWithText("won't change your suggestions", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(STYLE_MODEL_DOWNLOAD).performClick()
        assertEquals(1, downloads)
    }
}
