package com.wardrobapp.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.wardrobapp.data.UnrestorableReason
import com.wardrobapp.presentation.LanguageChoice
import com.wardrobapp.presentation.ThemeChoice
import com.wardrobapp.presentation.settingsView
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the settings screen actually shows.
 *
 * The first Compose tests in this project, and they exist because :app is the one
 * module with no coverage of the thing it is for. Everything decidable without a
 * device lives in :domain, :data and :presentation and is tested there; what is
 * left here is the layout and the platform plumbing, and one piece of that -- where
 * the database file landed -- was wrong for weeks precisely because no test could
 * see it.
 *
 * They need no [AppContainer]. That is not luck: every screen in this app takes a
 * plain state object and a handful of lambdas, so what it renders can be asked
 * without a database, a filesystem, or a ViewModel. Worth stating, because it is
 * the property that makes these tests cheap enough to keep writing.
 *
 * Run by Robolectric, so they go in the same `:app:test` task as everything else
 * and CI needs no emulator.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val version = AppVersion(name = "0.1-port", code = 1000)

    private fun show(
        state: SettingsViewModel.State,
        theme: ThemeChoice = ThemeChoice.SYSTEM,
        language: LanguageChoice = LanguageChoice.SYSTEM,
    ) {
        compose.setContent {
            SettingsScreen(
                state = state,
                version = version,
                language = language,
                onLanguageSelected = {},
                theme = theme,
                onThemeSelected = {},
                onBack = {},
                onBackupRequested = {},
                onBackupDismissed = {},
                onRestoreRequested = {},
                onRestoreConfirmed = {},
                onRestoreDismissed = {},
                onTidyRequested = {},
                onTidyDismissed = {},
                onRetry = {},
            )
        }
    }

    private fun loaded(garments: Long = 12, retired: Long = 0, photoBytes: Long = 5_242_880) =
        SettingsViewModel.State(
            loading = false,
            view = settingsView(garments = garments, retired = retired, photoBytes = photoBytes),
        )

    @Test
    fun `the storage figures are the ones presentation worked out`() {
        show(loaded(garments = 12, photoBytes = 5_242_880))

        compose.onNodeWithText("12").assertIsDisplayed()
        // 5 MiB, to one decimal place, with the unit the screen appends.
        compose.onNodeWithText("5.0 MB").assertIsDisplayed()
    }

    @Test
    fun `a wardrobe nobody has retired anything from says nothing about it`() {
        show(loaded(retired = 0))

        compose.onNodeWithText("No longer in use").assertDoesNotExist()
    }

    @Test
    fun `a wardrobe with retired garments says so`() {
        show(loaded(retired = 3))

        compose.onNodeWithText("No longer in use").assertIsDisplayed()
    }

    @Test
    fun `a read that failed does not look like an empty wardrobe`() {
        // The rule every screen in this app follows, and the one worth a test:
        // showing zero garments for a database that would not open is the kind of
        // wrong that makes someone restore a backup they did not need to.
        show(SettingsViewModel.State(loading = false, view = null, error = "disk I/O error"))

        compose.onNodeWithText("Couldn't read the wardrobe").assertIsDisplayed()
        compose.onNodeWithText("disk I/O error").assertIsDisplayed()
    }

    @Test
    fun `a running backup cannot be dismissed`() {
        show(loaded().copy(backup = SettingsViewModel.Backup.Running(percent = 40)))

        // No button at all: the work carries on whether the dialog is there or not,
        // and a half-written archive is not something to hand back silently.
        compose.onNodeWithText("Done").assertDoesNotExist()
        compose.onNodeWithText("Close").assertDoesNotExist()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun `a restore asks before it replaces anything`() {
        show(loaded().copy(restore = SettingsViewModel.Restore.Confirming))

        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun `a refused restore says why, from the reason rather than the exception`() {
        // The whole point of carrying reasons: the message is what tells someone
        // whether to update the app, find another file, or that nothing was lost.
        show(
            loaded().copy(
                restore = SettingsViewModel.Restore.Failed(
                    message = "should not be shown",
                    reason = UnrestorableReason.BackupFromNewerApp(found = 7, supported = 3),
                ),
            )
        )

        compose.onNodeWithText("Update the app", substring = true).assertIsDisplayed()
        compose.onNodeWithText("should not be shown").assertDoesNotExist()
    }

    @Test
    fun `a failure with no reason falls back to whatever threw`() {
        // Not every failure is one this app recognises -- a filesystem error is
        // somebody else's words, and dropping them would leave a dialog with no
        // diagnostic in it at all.
        show(
            loaded().copy(
                restore = SettingsViewModel.Restore.Failed(message = "Permission denied"),
            )
        )

        compose.onNodeWithText("Permission denied").assertIsDisplayed()
    }

    @Test
    fun `nothing to optimize is a different answer from nothing saved`() {
        show(loaded().copy(tidy = SettingsViewModel.Tidy.NothingToDo(examined = 4)))

        compose.onNodeWithText("4 cut-out photos", substring = true).assertIsDisplayed()
    }

    @Test
    fun `an optimize pass reports what it saved`() {
        show(loaded().copy(tidy = SettingsViewModel.Tidy.Done(shrunk = 3, megabytes = "1.4")))

        compose.onNodeWithText("3 photos shrunk", substring = true).assertIsDisplayed()
        compose.onNodeWithText("1.4 MB", substring = true).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "es")
    fun `a Spanish phone reads Spanish`() {
        // End to end, which nothing else can check: the resource lookup, the
        // translation, and the reason-to-resource mapping all have to be right for
        // this to pass, and `StringResourceParityTest` can only see the files.
        show(loaded())

        compose.onNodeWithText("Configuración").assertIsDisplayed()
        compose.onNodeWithText("Almacenamiento").assertIsDisplayed()
        compose.onNodeWithText("Prendas").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "es")
    fun `a refused restore is refused in Spanish too`() {
        // The case that motivated giving :data reasons instead of sentences.
        show(
            loaded().copy(
                restore = SettingsViewModel.Restore.Failed(
                    message = "unused",
                    reason = UnrestorableReason.NoDatabase,
                ),
            )
        )

        compose.onNodeWithText("Copia no válida", substring = true).assertIsDisplayed()
    }
}
