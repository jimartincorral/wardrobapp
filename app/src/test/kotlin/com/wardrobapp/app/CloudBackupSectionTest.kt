package com.wardrobapp.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.wardrobapp.data.DriveBackup
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Drive section of Settings.
 *
 * What is only true here, and cannot be asked of :data: that a finished restore
 * is visible, and that nothing can be pressed while something is already in
 * flight. Both are about a screen replacing somebody's wardrobe, where doing it
 * twice at once or doing it silently are the two ways to alarm them.
 *
 * The rules about *which* archive and *which* to prune live in :data, where they
 * are tested against JSON.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class CloudBackupSectionTest {

    @get:Rule
    val compose = createComposeRule()

    private var restoredDismissed = 0
    private var restoreAsked: DriveBackup? = null

    private val archive = DriveBackup(
        id = "abc",
        name = "wardrobapp-backup-2026-08-28T09-00-00-000Z.zip",
        modifiedAt = 1_756_371_600_000L,
    )

    private fun show(state: CloudBackupViewModel.State) {
        compose.setContent {
            CloudBackupSection(
                state = state,
                onConnect = {},
                onDisconnect = {},
                onBackUp = {},
                onRefresh = {},
                onRestore = { restoreAsked = it },
                onFailureDismissed = {},
                onRestoredDismissed = { restoredDismissed++ },
            )
        }
    }

    @Test
    fun `before there is permission, the only thing offered is connecting`() {
        show(CloudBackupViewModel.State(signedIn = false))

        compose.onNodeWithText("Connect Google Drive").assertIsDisplayed()
        compose.onNodeWithText("Back up to Drive").assertDoesNotExist()
    }

    @Test
    fun `a finished restore says so`() {
        // A restore replaces the wardrobe with no visible sign of it: the spinner
        // stops and the section looks exactly as it did. Saying nothing would leave
        // somebody unable to tell a finished restore from one that never ran.
        show(CloudBackupViewModel.State(signedIn = true, restored = true))

        compose.onNodeWithText("Wardrobe restored").assertIsDisplayed()
    }

    @Test
    fun `closing that confirmation is reported, so it does not come back`() {
        show(CloudBackupViewModel.State(signedIn = true, restored = true))

        compose.onNodeWithText("Done").performClick()

        assertEquals(1, restoredDismissed)
    }

    @Test
    fun `nothing can be started while something is already running`() {
        // Every one of these ends by asking Drive something, and two at once race
        // for the same folder -- a prune deciding what to keep from a listing taken
        // before another upload landed.
        show(
            CloudBackupViewModel.State(
                signedIn = true,
                backups = listOf(archive),
                working = CloudBackupViewModel.Working.BACKING_UP,
            ),
        )

        compose.onNodeWithText("Back up to Drive").assertIsNotEnabled()
        compose.onNodeWithText("Refresh").assertIsNotEnabled()
        compose.onNodeWithText("Disconnect").assertIsNotEnabled()
    }

    @Test
    fun `with nothing running, what is there can be acted on`() {
        show(CloudBackupViewModel.State(signedIn = true, backups = listOf(archive)))

        compose.onNodeWithText("Back up to Drive").assertIsEnabled()
        compose.onNodeWithText("Refresh").assertIsEnabled()
    }

    @Test
    fun `restoring asks before it does anything`() {
        // Restoring replaces the whole wardrobe, so one tap must not be enough.
        // `onNodeWithText` matches whole strings, so the row's button is the only
        // exact "Restore" on screen until the dialog adds its own.
        show(CloudBackupViewModel.State(signedIn = true, backups = listOf(archive)))

        compose.onNodeWithText("Restore").performClick()

        compose.onNodeWithText("Restore from Drive?").assertIsDisplayed()
        assertEquals(null, restoreAsked)
    }

    @Test
    fun `the archive on offer is named, so it is clear which one is being replaced`() {
        show(CloudBackupViewModel.State(signedIn = true, backups = listOf(archive)))

        compose.onNodeWithText(archive.name).assertIsDisplayed()
    }

    @Test
    fun `a failure is shown rather than swallowed`() {
        show(
            CloudBackupViewModel.State(
                signedIn = true,
                failure = "Drive refused the request (403).",
            ),
        )

        compose.onNodeWithText("Drive refused the request (403).").assertIsDisplayed()
    }

    @Test
    fun `an empty folder says so rather than looking unfinished`() {
        show(CloudBackupViewModel.State(signedIn = true))

        compose.onNodeWithText("No backups in Drive yet.").assertIsDisplayed()
    }
}
