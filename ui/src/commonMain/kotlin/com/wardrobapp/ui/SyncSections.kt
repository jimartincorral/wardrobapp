package com.wardrobapp.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wardrobapp.presentation.PhoneSyncState
import com.wardrobapp.presentation.SyncFailure
import com.wardrobapp.presentation.formatStoredDateTimeForReader
import com.wardrobapp.presentation.hostOfAddress
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
import com.wardrobapp.ui.resources.settings_sync_running
import com.wardrobapp.ui.resources.settings_sync_unreachable
import com.wardrobapp.ui.resources.settings_sync_wifi_only
import com.wardrobapp.ui.resources.settings_sync_wifi_only_hint
import com.wardrobapp.ui.resources.sync_pairing_code
import com.wardrobapp.ui.resources.sync_pairing_hint
import com.wardrobapp.ui.resources.sync_pairing_off
import com.wardrobapp.ui.resources.sync_pairing_reset
import com.wardrobapp.ui.resources.sync_pairing_reset_body
import com.wardrobapp.ui.resources.sync_pairing_reset_title
import org.jetbrains.compose.resources.stringResource

/**
 * Settings' sync section in the browser: what a phone needs to pair, shown to
 * somebody signed in to Home Assistant -- which is who reaches this page.
 *
 * The code is selectable, so it can be copied rather than retyped, and asks
 * for a monospaced face, where the five-character groups line up and a reader
 * can keep their place typing it into a phone. In a browser Compose has no
 * monospaced font to give it and falls back to its own; the groups and the
 * dashes still do most of the work.
 *
 * [port] is the one inside the app's container, which is what Home Assistant's
 * Network settings list; the host port it is given there is the person's
 * choice, and not something the server can know to show. Null when the server
 * has sync switched off, and [code] null until it has answered.
 */
@Composable
fun SyncPairingSection(code: String?, port: Int?, onResetConfirmed: () -> Unit) {
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
        Text(
            stringResource(Res.string.sync_pairing_off),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Text(
        stringResource(Res.string.sync_pairing_hint, port),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (code != null) {
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
}

/** The controls with no label text of their own to find, for the phone's tests. */
const val SYNC_ADDRESS = "sync-address"
const val SYNC_CODE = "sync-code"
const val SYNC_BACKGROUND = "sync-background"
const val SYNC_WIFI_ONLY = "sync-wifi-only"

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
) {
    Section(stringResource(Res.string.settings_section_home_assistant))

    val status = state.status
    val pairedWith = status.address
    if (pairedWith == null) {
        var address by rememberSaveable { mutableStateOf("") }
        var code by rememberSaveable { mutableStateOf("") }

        Text(
            stringResource(Res.string.settings_sync_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = address,
            onValueChange = {
                address = it
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
