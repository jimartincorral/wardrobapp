package com.wardrobapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wardrobapp.presentation.PhoneSyncState
import com.wardrobapp.presentation.QrCode
import com.wardrobapp.presentation.ScanProblem
import com.wardrobapp.presentation.SyncFailure
import com.wardrobapp.presentation.formatStoredDateTimeForReader
import com.wardrobapp.presentation.hostOfAddress
import com.wardrobapp.presentation.pairingLinkFor
import com.wardrobapp.presentation.phoneSyncAddressFor
import com.wardrobapp.ui.resources.Res
import com.wardrobapp.ui.resources.action_cancel
import com.wardrobapp.ui.resources.settings_section_home_assistant
import com.wardrobapp.ui.resources.settings_section_sync
import com.wardrobapp.ui.resources.settings_sync_address
import com.wardrobapp.ui.resources.settings_sync_address_example
import com.wardrobapp.ui.resources.settings_sync_address_invalid
import com.wardrobapp.ui.resources.settings_sync_background
import com.wardrobapp.ui.resources.settings_sync_code
import com.wardrobapp.ui.resources.settings_sync_connect
import com.wardrobapp.ui.resources.settings_sync_connecting
import com.wardrobapp.ui.resources.settings_sync_disconnect
import com.wardrobapp.ui.resources.settings_sync_failed
import com.wardrobapp.ui.resources.settings_sync_hint
import com.wardrobapp.ui.resources.settings_sync_last
import com.wardrobapp.ui.resources.settings_sync_never
import com.wardrobapp.ui.resources.settings_sync_not_paired
import com.wardrobapp.ui.resources.settings_sync_now
import com.wardrobapp.ui.resources.settings_sync_paired_with
import com.wardrobapp.ui.resources.settings_sync_restore_pending
import com.wardrobapp.ui.resources.settings_sync_running
import com.wardrobapp.ui.resources.settings_sync_scan
import com.wardrobapp.ui.resources.settings_sync_scan_not_pairing
import com.wardrobapp.ui.resources.settings_sync_scan_unavailable
import com.wardrobapp.ui.resources.settings_sync_scanned
import com.wardrobapp.ui.resources.settings_sync_server_too_old
import com.wardrobapp.ui.resources.settings_sync_unreachable
import com.wardrobapp.ui.resources.settings_sync_wifi_only
import com.wardrobapp.ui.resources.settings_sync_wifi_only_hint
import com.wardrobapp.ui.resources.sync_pairing_at_home
import com.wardrobapp.ui.resources.sync_pairing_closed
import com.wardrobapp.ui.resources.sync_pairing_code
import com.wardrobapp.ui.resources.sync_pairing_elsewhere
import com.wardrobapp.ui.resources.sync_pairing_hint
import com.wardrobapp.ui.resources.sync_pairing_off
import com.wardrobapp.ui.resources.sync_pairing_qr_description
import com.wardrobapp.ui.resources.sync_pairing_reset
import com.wardrobapp.ui.resources.sync_pairing_reset_body
import com.wardrobapp.ui.resources.sync_pairing_reset_title
import com.wardrobapp.ui.resources.sync_pairing_scan
import kotlin.math.floor
import org.jetbrains.compose.resources.stringResource

/**
 * Settings' sync section in the browser: what a phone needs to pair, shown to
 * somebody signed in to Home Assistant -- which is who reaches this page.
 *
 * At its best this is one QR code: the phone scans it from its own Settings,
 * or from its camera app, and finds the address and the code filled in (see
 * PairingLink.kt). That needs an address a phone can reach, made of the host
 * this browser reached Home Assistant at -- [browserHost] -- and the host
 * port Home Assistant publishes the sync port on, which the server asks Home
 * Assistant for. When either is missing, the section says what it can
 * instead, from most to least helpful:
 *
 *  - [hostPortKnown] and no [hostPort]: the port is closed, and nothing a
 *    phone is given will work until it is opened. Said first and plainly,
 *    since before this a phone would be paired against a closed port and
 *    told only that Home Assistant could not be reached.
 *  - A host port, but a host no phone can use -- Nabu Casa's remote address,
 *    or localhost -- and [homeHost], the machine's own address on the home
 *    network, which the server asked Home Assistant for: a QR code of that
 *    address, with a line saying it is the one at home.
 *  - The same without a [homeHost]: the port is named, and the reader is
 *    asked for the address they use at home.
 *  - Nothing known: the instructions there always were.
 *
 * The code is shown in every case and stays selectable, so it can be copied
 * rather than retyped, in a monospaced face where the five-character groups
 * line up. In a browser Compose has no monospaced font to give it and falls
 * back to its own; the groups and the dashes still do most of the work.
 *
 * [port] is the one inside the app's container, which is what Home
 * Assistant's Network settings list. Null when the server has sync switched
 * off, and [code] null until it has answered.
 */
@Composable
fun SyncPairingSection(
    code: String?,
    port: Int?,
    hostPortKnown: Boolean,
    hostPort: Int?,
    browserHost: String,
    onResetConfirmed: () -> Unit,
    homeHost: String? = null,
) {
    var confirming by remember { mutableStateOf(false) }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(Res.string.sync_pairing_reset_title)) },
            text = { Text(stringResource(Res.string.sync_pairing_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onResetConfirmed()
                }) { Text(stringResource(Res.string.sync_pairing_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }

    Section(stringResource(Res.string.settings_section_sync))

    if (port == null) {
        Hint(stringResource(Res.string.sync_pairing_off))
        return
    }

    // The browser's own host first, since it is the one name known to work
    // from where the reader is; the machine's home address only when that
    // host is no use to a phone, and the server could find one.
    val browserAddress = hostPort?.let { phoneSyncAddressFor(browserHost, it) }
    val homeAddress = if (browserAddress == null && hostPort != null && homeHost != null) {
        phoneSyncAddressFor(homeHost, hostPort)
    } else {
        null
    }
    val phoneAddress = browserAddress ?: homeAddress
    val closed = hostPortKnown && hostPort == null
    when {
        closed -> Text(
            stringResource(Res.string.sync_pairing_closed, port),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        homeAddress != null -> {
            Hint(stringResource(Res.string.sync_pairing_at_home))
            Hint(stringResource(Res.string.sync_pairing_scan))
        }
        phoneAddress != null -> Hint(stringResource(Res.string.sync_pairing_scan))
        hostPort != null -> Hint(stringResource(Res.string.sync_pairing_elsewhere, hostPort))
        else -> Hint(stringResource(Res.string.sync_pairing_hint, port))
    }

    if (code == null) return

    if (phoneAddress != null) {
        PairingQrCode(
            link = pairingLinkFor(phoneAddress, code),
            description = stringResource(Res.string.sync_pairing_qr_description),
            modifier = Modifier.padding(top = 12.dp),
        )
        // What the code holds, for a phone that cannot scan, and so the
        // reader can see where a phone is about to be sent.
        Column(modifier = Modifier.padding(top = 12.dp)) {
            Text(stringResource(Res.string.settings_sync_address), style = MaterialTheme.typography.labelMedium)
            SelectionContainer {
                Text(
                    phoneAddress.removeSuffix("/"),
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(stringResource(Res.string.sync_pairing_code), style = MaterialTheme.typography.labelMedium)
        SelectionContainer {
            Text(
                code,
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    OutlinedButton(
        onClick = { confirming = true },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Text(stringResource(Res.string.sync_pairing_reset))
    }
}

/**
 * [link] as a QR code: dark modules on white, with the four-module quiet zone
 * the standard asks for, whatever the theme.
 *
 * Never inverted for dark mode. Scanners are written for dark on light, and
 * many read nothing else; a white square in a dark page is the price of one
 * that every phone can read. The modules are snapped to whole pixels, since a
 * module that falls across two is drawn as two grey ones and a camera has to
 * guess which side it belonged to.
 */
@Composable
private fun PairingQrCode(link: String, description: String, modifier: Modifier = Modifier) {
    val qr = remember(link) { QrCode.encode(link) } ?: return
    Canvas(
        modifier = modifier
            .size(224.dp)
            .semantics { contentDescription = description }
            .testTag(SYNC_PAIRING_QR),
    ) {
        val modules = qr.size + QUIET_ZONE * 2
        val cell = floor(size.minDimension / modules).coerceAtLeast(1f)
        val origin = Offset(
            x = floor((size.width - cell * modules) / 2) + cell * QUIET_ZONE,
            y = floor((size.height - cell * modules) / 2) + cell * QUIET_ZONE,
        )
        drawRect(Color.White)
        for (y in 0 until qr.size) {
            for (x in 0 until qr.size) {
                if (qr.isDark(x, y)) {
                    drawRect(Color.Black, topLeft = origin + Offset(x * cell, y * cell), size = Size(cell, cell))
                }
            }
        }
    }
}

private const val QUIET_ZONE = 4

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The QR code in the browser's sync section, for its tests. */
const val SYNC_PAIRING_QR = "sync-pairing-qr"

/** The controls with no label text of their own to find, for the phone's tests. */
const val SYNC_ADDRESS = "sync-address"
const val SYNC_CODE = "sync-code"
const val SYNC_BACKGROUND = "sync-background"
const val SYNC_WIFI_ONLY = "sync-wifi-only"
const val SYNC_SCAN = "sync-scan"

/**
 * Settings' sync section on the phone: pairing with Home Assistant, and then
 * how syncing is going.
 *
 * Before pairing it is a paragraph and two fields, the address and the code,
 * since that is all there is to do. After, the address is shown rather than
 * edited -- changing where a phone syncs is stopping and pairing again, which
 * makes it a decision rather than a typo -- with when it last synced, and the
 * two switches.
 *
 * The fields are held here rather than in the model: they are what somebody is
 * in the middle of typing, which nothing else needs to know until they press
 * Connect. Saved across rotation, the code included -- it lives in the
 * activity's saved state for as long as that does, which is no longer than it
 * sits on the screen.
 *
 * Above them, Scan pairing code, when [onScanRequested] is given: the browser
 * shows a QR code holding both, and scanning it fills both in. Filled in, not
 * connected -- the person still presses Connect with the address in front of
 * them, and a line under the fields says so, because a form that filled itself
 * in and then waited would otherwise look stuck. See PairingLink.kt for why.
 * Null where there is no scanner to open, and then the form is as it was.
 */
@Composable
fun PhoneSyncSection(
    state: PhoneSyncState,
    onConnect: (address: String, code: String) -> Unit,
    onFormEdited: () -> Unit,
    onSyncNow: () -> Unit,
    onDisconnect: () -> Unit,
    onBackgroundChanged: (Boolean) -> Unit,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onScanRequested: (() -> Unit)? = null,
    onOfferTaken: () -> Unit = {},
) {
    Section(stringResource(Res.string.settings_section_home_assistant))

    val status = state.status
    val pairedWith = status.address
    if (pairedWith == null) {
        var address by rememberSaveable { mutableStateOf("") }
        var code by rememberSaveable { mutableStateOf("") }
        var filledIn by rememberSaveable { mutableStateOf(false) }

        // Taken once: the model forgets the offer as soon as the fields hold
        // it, so a rotation does not put it back over what was typed since.
        LaunchedEffect(state.offer) {
            val offer = state.offer ?: return@LaunchedEffect
            address = offer.address.removeSuffix("/")
            code = offer.code
            filledIn = true
            onOfferTaken()
        }

        Text(
            stringResource(Res.string.settings_sync_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onScanRequested != null) {
            Button(
                onClick = onScanRequested,
                enabled = !state.connecting,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(SYNC_SCAN),
            ) {
                Text(stringResource(Res.string.settings_sync_scan))
            }
        }
        state.scanProblem?.let { problem ->
            Text(
                when (problem) {
                    ScanProblem.NOT_A_PAIRING_CODE -> stringResource(Res.string.settings_sync_scan_not_pairing)
                    ScanProblem.SCANNER_UNAVAILABLE -> stringResource(Res.string.settings_sync_scan_unavailable)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        OutlinedTextField(
            value = address,
            onValueChange = {
                address = it
                filledIn = false
                onFormEdited()
            },
            label = { Text(stringResource(Res.string.settings_sync_address)) },
            placeholder = { Text(stringResource(Res.string.settings_sync_address_example)) },
            singleLine = true,
            enabled = !state.connecting,
            isError = state.addressInvalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(SYNC_ADDRESS),
        )
        OutlinedTextField(
            value = code,
            onValueChange = {
                code = it
                filledIn = false
                onFormEdited()
            },
            label = { Text(stringResource(Res.string.settings_sync_code)) },
            singleLine = true,
            enabled = !state.connecting,
            // The code is shown in capitals, in groups; typing it the same way
            // is easier to check against the screen it is read from. The
            // server ignores case either way.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(SYNC_CODE),
        )

        val problem = when {
            state.addressInvalid -> stringResource(Res.string.settings_sync_address_invalid)
            else -> state.connectFailure?.let { failureText(it) }
        }
        if (filledIn && problem == null && !state.connecting) {
            Text(
                stringResource(Res.string.settings_sync_scanned, hostOfAddress(address) ?: address),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (problem != null) {
            Text(
                problem,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Button(
            onClick = { onConnect(address, code) },
            enabled = !state.connecting && address.isNotBlank() && code.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            if (state.connecting) {
                Busy()
                Text(stringResource(Res.string.settings_sync_connecting))
            } else {
                Text(stringResource(Res.string.settings_sync_connect))
            }
        }
        return
    }

    Text(
        stringResource(Res.string.settings_sync_paired_with, hostOfAddress(pairedWith) ?: pairedWith),
        style = MaterialTheme.typography.bodyMedium,
    )

    // When it was last in step, and why it is not now, both: a failure on its
    // own would not say how far behind the phone is, and a time on its own
    // would look like a sync that is still working.
    Text(
        status.lastSyncedAt
            ?.let { stringResource(Res.string.settings_sync_last, formatStoredDateTimeForReader(it)) }
            ?: stringResource(Res.string.settings_sync_never),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    if (status.restorePending) {
        Text(
            stringResource(Res.string.settings_sync_restore_pending),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    status.lastFailure?.let { failure ->
        Text(
            stringResource(Res.string.settings_sync_failed, failureText(failure)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 12.dp),
    ) {
        Button(onClick = onSyncNow, enabled = !status.syncing) {
            if (status.syncing) {
                Busy()
                Text(stringResource(Res.string.settings_sync_running))
            } else {
                Text(stringResource(Res.string.settings_sync_now))
            }
        }
        OutlinedButton(onClick = onDisconnect) {
            Text(stringResource(Res.string.settings_sync_disconnect))
        }
    }

    SwitchRow(
        label = stringResource(Res.string.settings_sync_background),
        checked = status.background,
        onCheckedChange = onBackgroundChanged,
        tag = SYNC_BACKGROUND,
    )
    SwitchRow(
        label = stringResource(Res.string.settings_sync_wifi_only),
        checked = status.wifiOnly,
        onCheckedChange = onWifiOnlyChanged,
        tag = SYNC_WIFI_ONLY,
    )
    Text(
        stringResource(Res.string.settings_sync_wifi_only_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun failureText(failure: SyncFailure): String = when (failure) {
    SyncFailure.NotPaired -> stringResource(Res.string.settings_sync_not_paired)
    SyncFailure.Unreachable -> stringResource(Res.string.settings_sync_unreachable)
    SyncFailure.ServerTooOld -> stringResource(Res.string.settings_sync_server_too_old)
    is SyncFailure.Other -> failure.message
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, tag: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun Busy() {
    CircularProgressIndicator(
        strokeWidth = 2.dp,
        modifier = Modifier.size(16.dp),
    )
    Spacer(Modifier.size(8.dp))
}
