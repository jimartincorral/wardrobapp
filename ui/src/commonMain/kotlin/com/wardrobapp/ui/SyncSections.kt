package com.wardrobapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wardrobapp.ui.resources.Res
import com.wardrobapp.ui.resources.action_cancel
import com.wardrobapp.ui.resources.settings_section_sync
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
