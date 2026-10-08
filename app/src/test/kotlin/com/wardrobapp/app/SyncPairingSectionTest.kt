package com.wardrobapp.app

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.wardrobapp.ui.SYNC_PAIRING_QR
import com.wardrobapp.ui.SyncPairingSection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The browser's sync section, drawn here because :ui's screens are only
 * drawn under test where there is an SDK. Which of its four answers it gives:
 * a QR code, a closed port, a host no phone can use, and nothing known.
 * What the QR code holds is PairingLinkTest's and QrCodeTest's, in
 * :presentation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class SyncPairingSectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val code = "ABCDE-FGHIJ-KMNPQ-RSTVW"

    private fun show(hostPortKnown: Boolean, hostPort: Int?, browserHost: String = "homeassistant.local", homeHost: String? = null) {
        compose.setContent {
            Column {
                SyncPairingSection(
                    code = code,
                    port = 8100,
                    hostPortKnown = hostPortKnown,
                    hostPort = hostPort,
                    browserHost = browserHost,
                    onResetConfirmed = {},
                    homeHost = homeHost,
                )
            }
        }
    }

    @Test
    fun `an open port and a usable host is a code to scan, and what it holds`() {
        show(hostPortKnown = true, hostPort = 18100)

        compose.onNodeWithTag(SYNC_PAIRING_QR).assertIsDisplayed()
        compose.onNodeWithContentDescription("QR code for pairing a phone").assertIsDisplayed()
        compose.onNodeWithText("http://homeassistant.local:18100").assertIsDisplayed()
        compose.onNodeWithText(code).assertIsDisplayed()
    }

    @Test
    fun `a closed port says to open it, and shows no code to scan`() {
        show(hostPortKnown = true, hostPort = null)

        compose.onNodeWithText("Phones can't reach this app yet", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(SYNC_PAIRING_QR).assertDoesNotExist()
    }

    @Test
    fun `Nabu Casa's address asks for the one used at home`() {
        show(hostPortKnown = true, hostPort = 8100, browserHost = "abc123.ui.nabu.casa")

        compose.onNodeWithText("open Home Assistant at the address you use at home", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(SYNC_PAIRING_QR).assertDoesNotExist()
        compose.onNodeWithText(code).assertIsDisplayed()
    }

    @Test
    fun `through Nabu Casa with the home address known, the code holds that address and says so`() {
        show(hostPortKnown = true, hostPort = 8100, browserHost = "abc123.ui.nabu.casa", homeHost = "192.168.1.10")

        compose.onNodeWithTag(SYNC_PAIRING_QR).assertIsDisplayed()
        compose.onNodeWithText("its address on your home network", substring = true).assertIsDisplayed()
        compose.onNodeWithText("http://192.168.1.10:8100").assertIsDisplayed()
    }

    @Test
    fun `at home, the browser's own host wins over the machine's address`() {
        show(hostPortKnown = true, hostPort = 18100, homeHost = "192.168.1.10")

        compose.onNodeWithText("http://homeassistant.local:18100").assertIsDisplayed()
        compose.onNodeWithText("its address on your home network", substring = true).assertDoesNotExist()
    }

    @Test
    fun `knowing nothing gives the instructions there always were`() {
        show(hostPortKnown = false, hostPort = null)

        compose.onNodeWithText("give port 8100 a host port", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(SYNC_PAIRING_QR).assertDoesNotExist()
    }
}
