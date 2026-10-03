package com.wardrobapp.app

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.wardrobapp.presentation.PhoneSyncState
import com.wardrobapp.presentation.PhoneSyncStatus
import com.wardrobapp.presentation.SyncFailure
import com.wardrobapp.ui.PhoneSyncSection
import com.wardrobapp.ui.SYNC_ADDRESS
import com.wardrobapp.ui.SYNC_BACKGROUND
import com.wardrobapp.ui.SYNC_CODE
import com.wardrobapp.ui.SYNC_WIFI_ONLY
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Home Assistant section of Settings, as drawn. What pairing and syncing
 * do is PhoneSync's and PhoneSyncModel's, tested without a phone; this is what
 * somebody sees of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class PhoneSyncSectionTest {

    @get:Rule
    val compose = createComposeRule()

    private var connected: Pair<String, String>? = null
    private var syncs = 0
    private var backgroundWanted: Boolean? = null
    private var wifiOnlyWanted: Boolean? = null

    private fun show(state: PhoneSyncState) {
        compose.setContent {
            // In Settings the section is part of the screen's column.
            Column {
                PhoneSyncSection(
                    state = state,
                    onConnect = { address, code -> connected = address to code },
                    onFormEdited = {},
                    onSyncNow = { syncs++ },
                    onDisconnect = {},
                    onBackgroundChanged = { backgroundWanted = it },
                    onWifiOnlyChanged = { wifiOnlyWanted = it },
                )
            }
        }
    }

    @Test
    fun `an unpaired phone is asked for an address and a code, both`() {
        show(PhoneSyncState())

        compose.onNodeWithText("Connect").assertIsNotEnabled()
        compose.onNodeWithTag(SYNC_ADDRESS).performTextInput("homeassistant.local")
        compose.onNodeWithText("Connect").assertIsNotEnabled()
        compose.onNodeWithTag(SYNC_CODE).performTextInput("ABCDE-FGHIJ-KMNPQ-RSTVW")
        compose.onNodeWithText("Connect").assertIsEnabled().performClick()

        assertEquals("homeassistant.local" to "ABCDE-FGHIJ-KMNPQ-RSTVW", connected)
    }

    @Test
    fun `a code turned down says so`() {
        show(PhoneSyncState(connectFailure = SyncFailure.NotPaired))

        compose.onNodeWithText("Home Assistant did not accept that code", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a paired phone shows where, when, and why not`() {
        show(
            PhoneSyncState(
                status = PhoneSyncStatus(
                    address = "http://homeassistant.local:8100/",
                    lastSyncedAt = "2026-10-03T10:00:00.000Z",
                    lastFailure = SyncFailure.Unreachable,
                    background = true,
                    wifiOnly = false,
                ),
            ),
        )

        compose.onNodeWithText("Syncing with homeassistant.local").assertIsDisplayed()
        compose.onNodeWithText("Last synced:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Last sync failed: Couldn't reach Home Assistant", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(SYNC_ADDRESS).assertDoesNotExist()

        compose.onNodeWithText("Sync now").performClick()
        assertEquals(1, syncs)
        compose.onNodeWithTag(SYNC_BACKGROUND).assertIsOn().performClick()
        assertEquals(false, backgroundWanted)
        compose.onNodeWithTag(SYNC_WIFI_ONLY).assertIsOff().performClick()
        assertEquals(true, wifiOnlyWanted)
    }

    @Test
    fun `sync now cannot be pressed while a sync runs`() {
        show(PhoneSyncState(status = PhoneSyncStatus(address = "http://ha:8100/", syncing = true)))

        compose.onNodeWithText("Syncing…").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Not synced yet.").assertIsDisplayed()
    }
}
