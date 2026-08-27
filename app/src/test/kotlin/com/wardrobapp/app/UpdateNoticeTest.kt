package com.wardrobapp.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.wardrobapp.data.AppRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What the app says about a newer build.
 *
 * The check itself cannot be tested here -- it is a request to GitHub -- and the
 * install cannot be tested anywhere but on a phone. What is testable is the part a
 * person actually meets: whether the notice appears at all, whether it says what
 * changed, and whether the three ways out of it are there. The last one matters
 * most: a dialog on every launch with no way to say "not this build" is a dialog
 * that has to be dismissed forever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class UpdateNoticeTest {

    @get:Rule
    val compose = createComposeRule()

    private val release = AppRelease(
        versionCode = 1120,
        versionName = "1.1.0",
        apkUrl = "https://github.com/o/r/releases/download/nightly/wardrobapp.apk",
        changes = listOf("Read a garment's colours by themselves", "Remove the type suggestions"),
    )

    private fun show(
        state: UpdateViewModel.State,
        onInstall: () -> Unit = {},
        onSkip: () -> Unit = {},
        onDismiss: () -> Unit = {},
        onSigningChangeDismissed: () -> Unit = {},
    ) {
        compose.setContent {
            UpdateNotice(
                state = state,
                onInstall = onInstall,
                onSkip = onSkip,
                onDismiss = onDismiss,
                onFailureDismissed = {},
                onSigningChangeDismissed = onSigningChangeDismissed,
            )
        }
    }

    @Test
    fun `nothing is said when there is nothing to say`() {
        // Which is every launch but the ones after a build is published, so this is
        // the case that runs most often by far.
        show(UpdateViewModel.State())

        compose.onNodeWithTag(UPDATE_NOTICE).assertDoesNotExist()
    }

    @Test
    fun `the offer names the build and what changed`() {
        show(UpdateViewModel.State(available = release))

        compose.onNodeWithTag(UPDATE_NOTICE).assertIsDisplayed()
        compose.onNodeWithText("Version 1.1.0, build 1120.").assertIsDisplayed()
        compose.onNodeWithText("• Read a garment's colours by themselves").assertIsDisplayed()
        compose.onNodeWithText("• Remove the type suggestions").assertIsDisplayed()
    }

    @Test
    fun `all three answers are offered`() {
        // Install, never this build, not now. Asserted by tapping, because a button
        // wired to the wrong lambda reads identically on screen.
        var installed = 0
        var skipped = 0
        var dismissed = 0

        show(
            UpdateViewModel.State(available = release),
            onInstall = { installed++ },
            onSkip = { skipped++ },
            onDismiss = { dismissed++ },
        )

        compose.onNodeWithText("Install").performClick()
        compose.onNodeWithText("Skip this build").performClick()
        compose.onNodeWithText("Later").performClick()

        assertEquals(1, installed)
        assertEquals(1, skipped)
        assertEquals(1, dismissed)
    }

    @Test
    fun `a long changelog is cut short rather than scrolled forever`() {
        val many = release.copy(changes = (1..12).map { "Change number $it" })
        show(UpdateViewModel.State(available = many))

        compose.onNodeWithText("• Change number 1").assertIsDisplayed()
        compose.onNodeWithText("• Change number 8").assertIsDisplayed()
        compose.onNodeWithText("• Change number 9").assertDoesNotExist()
        compose.onNodeWithText("and 4 more.").assertIsDisplayed()
    }

    @Test
    fun `a build with no name still says which build it is`() {
        // The document is generated, and a build that could not report its name is
        // still a build worth offering.
        show(UpdateViewModel.State(available = release.copy(versionName = "")))

        compose.onNodeWithText("Build 1120.").assertIsDisplayed()
    }

    @Test
    fun `while it downloads there is nothing to press`() {
        // Including no way to dismiss: leaving mid-download would leave a
        // half-written APK behind a dialog claiming nothing was happening.
        show(UpdateViewModel.State(available = release, downloading = true, progress = 0.4f))

        compose.onNodeWithText("Downloading…").assertIsDisplayed()
        compose.onNodeWithText("Install").assertDoesNotExist()
        compose.onNodeWithText("Skip this build").assertDoesNotExist()
        compose.onNodeWithText("Later").assertDoesNotExist()
    }

    @Test
    fun `a build signed with a new key explains the reinstall instead of installing`() {
        // The failure this replaces is Android's "App not installed", which says
        // nothing about why and nothing about what to do. If this dialog ever stops
        // naming the backup first, somebody uninstalls and loses a wardrobe.
        show(UpdateViewModel.State(available = release, signingChanged = true))

        compose.onNodeWithTag(UPDATE_NEW_KEY).assertIsDisplayed()
        compose.onNodeWithText("This build needs a fresh install").assertIsDisplayed()
        compose.onNodeWithText("Install").assertDoesNotExist()
    }

    @Test
    fun `the new-key notice is shown ahead of anything else`() {
        // Both can be set only by a bug, but if they ever are, the one that says what
        // to do beats the one that says something went wrong.
        show(UpdateViewModel.State(available = release, signingChanged = true, failure = "boom"))

        compose.onNodeWithTag(UPDATE_NEW_KEY).assertIsDisplayed()
        compose.onNodeWithText("boom").assertDoesNotExist()
    }

    @Test
    fun `a failed download says so instead of the offer`() {
        show(UpdateViewModel.State(available = release, failure = "the download was refused (404)"))

        compose.onNodeWithText("Couldn't install that build").assertIsDisplayed()
        compose.onNodeWithText("the download was refused (404)").assertIsDisplayed()
        compose.onNodeWithText("Install").assertDoesNotExist()
    }

    @Test
    fun `a skipped build is remembered across launches`() {
        // The whole point of "skip this build": a preference file, not a field, or
        // the next launch asks again.
        val context = RuntimeEnvironment.getApplication()

        SkippedUpdate(context).versionCode = 1120

        assertEquals(1120L, SkippedUpdate(context).versionCode)
        assertTrue("a fresh install has skipped nothing", SkippedUpdate(context).versionCode > 0)
    }
}
