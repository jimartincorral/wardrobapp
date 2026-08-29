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
import androidx.compose.material3.Switch
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
import com.wardrobapp.data.isoTimestamp
import com.wardrobapp.presentation.formatMegabytes
import com.wardrobapp.presentation.formatStoredDateTime
import java.util.Locale
import java.util.TimeZone

/** For the tests that ask whether the section is on screen. */
const val CLOUD_SECTION = "cloud-section"

/** The weekly-backup switch, which has no label text of its own to find. */
const val CLOUD_SCHEDULE = "cloud-schedule"

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
    onRestoredDismissed: () -> Unit,
    onScheduleChanged: (Boolean) -> Unit,
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

    // The same two strings the local restore uses. A restore that arrived from
    // Drive is the same event as one that arrived from the file picker, and saying
    // it differently would imply it was not.
    if (state.restored) {
        AlertDialog(
            onDismissRequest = onRestoredDismissed,
            title = { Text(stringResource(R.string.restore_done_title)) },
            text = { Text(stringResource(R.string.restore_done_body)) },
            confirmButton = {
                TextButton(onClick = onRestoredDismissed) {
                    Text(stringResource(R.string.action_done))
                }
            },
        )
    }

    confirming?.let { backup ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.settings_cloud_restore_title)) },
            text = {
                // Which one, not merely whether. The list is five rows of the same
                // sentence, so a confirmation that did not name the archive would
                // be asking about whichever was tapped -- and the tap is exactly
                // what somebody might have got wrong.
                Column {
                    Text(
                        stringResource(
                            R.string.restore_preview_made,
                            formatStoredDateTime(
                                isoTimestamp(backup.modifiedAt),
                                TimeZone.getDefault(),
                                Locale.getDefault(),
                            ),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.settings_cloud_restore_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            },
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
                    // The date rather than the file name. Drive names these by
                    // timestamp -- `wardrobapp-backup-2026-08-28T09-00-00-000Z.zip`
                    // -- which is a date nobody reads at a glance, and the folder
                    // holds five of them. `modifiedTime` came back with the listing,
                    // so this costs no request.
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            formatStoredDateTime(
                                isoTimestamp(backup.modifiedAt),
                                TimeZone.getDefault(),
                                Locale.getDefault(),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        backup.bytes?.let { bytes ->
                            Text(
                                stringResource(R.string.settings_megabytes, formatMegabytes(bytes)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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

            // The weekly backup, under the list rather than above it: what is in
            // Drive answers "is my wardrobe safe", and this answers "will it stay
            // safe" -- which is the second question somebody asks, not the first.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.settings_cloud_schedule),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.scheduled,
                    onCheckedChange = onScheduleChanged,
                    enabled = idle,
                    modifier = Modifier.testTag(CLOUD_SCHEDULE),
                )
            }

            Text(
                stringResource(R.string.settings_cloud_schedule_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // What the schedule has actually done, which is the only way an
            // unattended job can report anything: nobody is watching it run, so a
            // week of silent failure would otherwise look exactly like a week of
            // success.
            state.lastRunAt?.let { at ->
                val when_ = formatStoredDateTime(
                    isoTimestamp(at),
                    TimeZone.getDefault(),
                    Locale.getDefault(),
                )

                Text(
                    state.lastRunFailure
                        ?.let { stringResource(R.string.settings_cloud_last_failed, it) }
                        ?: stringResource(R.string.settings_cloud_last_backup, when_),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.lastRunFailure != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 4.dp),
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
