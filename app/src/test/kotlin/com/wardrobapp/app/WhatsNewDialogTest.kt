package com.wardrobapp.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.wardrobapp.data.AppRelease
import com.wardrobapp.data.ReleaseNote
import com.wardrobapp.data.ReleaseNoteKind
import com.wardrobapp.presentation.AppDestination
import com.wardrobapp.ui.WHATS_NEW
import com.wardrobapp.ui.WhatsNewDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a build that was just installed says it changed, and the update dialog's
 * notes in the reader's language. Which notes, and when, is :presentation's
 * whatsNewDecision, tested there; this is what is drawn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class WhatsNewDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val notes = listOf(
        ReleaseNote(1130, "Ratings stick.", ReleaseNoteKind.FIXED, textEs = "Las valoraciones se mantienen."),
        ReleaseNote(1130, "Sync with Home Assistant.", ReleaseNoteKind.NEW, textEs = "Sincroniza con Home Assistant.", destination = "settings"),
        ReleaseNote(1129, "Written by a later build.", ReleaseNoteKind.IMPROVED, destination = "somewhere-new"),
    )

    private var shown: AppDestination? = null
    private var dismissed = 0

    private fun show() {
        compose.setContent {
            WhatsNewDialog(notes = notes, onShow = { shown = it }, onDismiss = { dismissed++ })
        }
    }

    @Test
    fun `the notes are under their headings, and a known destination can be visited`() {
        show()

        compose.onNodeWithTag(WHATS_NEW).assertIsDisplayed()
        compose.onNodeWithText("New").assertIsDisplayed()
        compose.onNodeWithText("Improved").assertIsDisplayed()
        compose.onNodeWithText("Fixed").assertIsDisplayed()
        compose.onNodeWithText("• Sync with Home Assistant.").assertIsDisplayed()

        // One link: the destination this build has never heard of gets none.
        assertEquals(1, compose.onAllNodesWithText("Show me").fetchSemanticsNodes().size)
        compose.onNodeWithText("Show me").performClick()
        assertEquals(AppDestination.SETTINGS, shown)

        compose.onNodeWithText("Got it").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    @Config(qualifiers = "es-w411dp-h2000dp")
    fun `in Spanish, a note reads in Spanish when it has it and in English when it does not`() {
        show()

        compose.onNodeWithText("Novedades").assertIsDisplayed()
        compose.onNodeWithText("• Sincroniza con Home Assistant.").assertIsDisplayed()
        compose.onNodeWithText("• Written by a later build.").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "es-w411dp-h2000dp")
    fun `the update dialog's notes are in the reader's language too`() {
        val release = AppRelease(
            versionCode = 1130,
            versionName = "1.1.0",
            apkUrl = "https://github.com/o/r/releases/download/nightly/wardrobapp.apk",
            changes = notes.map { it.text },
            notes = notes,
        )
        compose.setContent {
            UpdateNotice(
                state = UpdateViewModel.State(available = release),
                onInstall = {},
                onSkip = {},
                onDismiss = {},
                onFailureDismissed = {},
            )
        }

        compose.onNodeWithText("• Las valoraciones se mantienen.").assertIsDisplayed()
    }
}
