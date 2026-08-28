package com.wardrobapp.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wardrobapp.data.DriveBackup

/** For the tests that ask whether the section is on screen. */
const val CLOUD_SECTION = "cloud-section"

/**
 * Backups in somebody's own Google Drive.
 *
 * Two states rather than one screen: before there is permission this is a
 * paragraph and a button, and after it there is a list of what is up there. Not a
 * separate screen, because it is the same question as the backup section above it
 * -- where does a copy of this wardrobe go -- and answering it in two places would
 * make them look like two features.
 */
@Composable
fun CloudBackupSection(
    state: CloudBackupViewModel.State,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onBackUp: () -> Unit,
    onRefresh: () -> Unit,
    onRestore: (DriveBackup) -> Unit,
    onFailureDismissed: () -> Unit,
) {
    var confirming by remember { mutableStateOf<DriveBackup?>(null) }

    state.failure?.let { failure ->
        AlertDialog(
            onDismissRequest = onFailureDismissed,
            title = { Text(stringResource(R.string.settings_section_cloud)) },
            text = { Text(failure) },
            confirmButton = {
                TextButton(onClick = onFailureDismissed) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    confirming?.let { backup ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.settings_cloud_restore_title)) },
            text = { Text(stringResource(R.string.settings_cloud_restore_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = null
                        onRestore(backup)
                    },
                ) {
                    Text(stringResource(R.string.settings_cloud_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Column(modifier = Modifier.fillMaxWidth().testTag(CLOUD_SECTION)) {
        Text(
            stringResource(R.string.settings_cloud_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Nothing may overlap: each of these ends by asking Drive something, and
        // two at once would race for the same folder.
        val idle = state.working == null

        if (!state.signedIn) {
            Button(
                onClick = onConnect,
                enabled = idle,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Text(stringResource(R.string.settings_cloud_connect))
            }
        } else {
            Button(
                onClick = onBackUp,
                enabled = idle,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Text(stringResource(R.string.settings_cloud_backup_now))
            }

            if (state.backups.isEmpty() && idle) {
                Text(
                    stringResource(R.string.settings_cloud_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            for (backup in state.backups) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        backup.name,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { confirming = backup }, enabled = idle) {
                        Text(stringResource(R.string.settings_cloud_restore))
                    }
                }
            }

            if (state.backups.isNotEmpty()) {
                Text(
                    stringResource(R.string.settings_cloud_kept),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                OutlinedButton(onClick = onRefresh, enabled = idle) {
                    Text(stringResource(R.string.settings_cloud_refresh))
                }
                OutlinedButton(onClick = onDisconnect, enabled = idle) {
                    Text(stringResource(R.string.settings_cloud_disconnect))
                }
            }

            Text(
                stringResource(R.string.settings_cloud_disconnect_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        state.working?.let { Working(it, state.progress) }
    }
}


/** What is happening, and how far along it is where that is known. */
@Composable
private fun Working(working: CloudBackupViewModel.Working, progress: Float?) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
            Text(
                stringResource(
                    when (working) {
                        CloudBackupViewModel.Working.CONNECTING -> R.string.settings_cloud_connecting
                        CloudBackupViewModel.Working.LISTING -> R.string.settings_cloud_listing
                        CloudBackupViewModel.Working.BACKING_UP -> R.string.settings_cloud_backing_up
                        CloudBackupViewModel.Working.RESTORING -> R.string.settings_cloud_restoring
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        // A bar only once there is something true to draw, as elsewhere: a
        // transfer whose size nobody declared would otherwise show one stuck at
        // nothing.
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}
