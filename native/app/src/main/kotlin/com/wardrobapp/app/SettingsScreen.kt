package com.wardrobapp.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wardrobapp.presentation.LanguageChoice

/**
 * Settings.
 *
 * Layout only. What the storage figures read as and how full the backup bar is
 * were decided in :presentation before anything reached here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsViewModel.State,
    version: AppVersion,
    /**
     * The language in force, read from the platform rather than from this app's
     * own state: AppCompat owns the choice, and a copy of it here could disagree
     * with what Android's per-app language screen shows.
     */
    language: LanguageChoice,
    onLanguageSelected: (LanguageChoice) -> Unit,
    onBack: () -> Unit,
    onBackupRequested: () -> Unit,
    onBackupDismissed: () -> Unit,
    onRestoreRequested: () -> Unit,
    onRestoreConfirmed: () -> Unit,
    onRestoreDismissed: () -> Unit,
    onRetry: () -> Unit,
) {
    state.backup?.let { backup ->
        BackupDialog(backup, onBackupDismissed)
    }
    state.restore?.let { restore ->
        RestoreDialog(restore, onRestoreConfirmed, onRestoreDismissed)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { insets ->
        // Bound once rather than smart-cast through the branches below, which is
        // how the other screens read it too.
        val view = state.view

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Section("Storage")
            when {
                view != null -> {
                    Figure("Garments", view.garments.toString())
                    // Only when there is something to say: a wardrobe nobody has
                    // retired anything from does not need a row reading zero.
                    if (view.retired > 0) {
                        Figure("No longer in use", view.retired.toString())
                    }
                    Figure("Photos", "${view.photoMegabytes} MB")
                }

                state.loading -> Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }

                // A read that failed is not an empty wardrobe, and must not look
                // like one. Same rule as every other screen.
                else -> Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text("Couldn't read the wardrobe", style = MaterialTheme.typography.bodyMedium)
                    state.error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(onClick = onRetry) { Text("Try again") }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Section("Backup")
            Text(
                "A backup is one file holding the wardrobe and every photo. It is " +
                    "also how a wardrobe moves between this app and the one on the " +
                    "Play Store.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onBackupRequested,
                enabled = state.backup !is SettingsViewModel.Backup.Running,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Text("Create a backup")
            }
            OutlinedButton(
                onClick = onRestoreRequested,
                enabled = state.restore == null && state.backup !is SettingsViewModel.Backup.Running,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text("Restore from a backup")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Section(stringResource(R.string.settings_language))
            Text(
                stringResource(R.string.settings_language_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                for (choice in LanguageChoice.entries) {
                    FilterChip(
                        selected = choice == language,
                        onClick = { onLanguageSelected(choice) },
                        label = { Text(stringResource(choice.labelRes)) },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Section("About")
            // Read from the installed package rather than written here. The
            // React Native app hardcodes its version string, which means it has
            // been reporting 1.0.0 for every build it ever shipped.
            Figure("Version", version.name)
            Figure("Build", version.code.toString())

            // Room to scroll clear of the gesture area at the bottom.
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** The app's own version, as the installed package reports it. */
data class AppVersion(val name: String, val code: Long)

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Figure(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * What a backup has to say.
 *
 * The running dialog cannot be dismissed: the work continues whether it is on
 * screen or not, and a half-written archive is not something to hand back
 * silently. Unlike a restore there is no confirmation, because writing a new
 * file destroys nothing -- the file picker already asked where to put it.
 */
@Composable
private fun BackupDialog(
    backup: SettingsViewModel.Backup,
    onDismiss: () -> Unit,
) = when (backup) {
    is SettingsViewModel.Backup.Running -> AlertDialog(
        onDismissRequest = {},
        title = { Text("Backing up") },
        text = {
            Column {
                Text("Copying the wardrobe and its photos.")
                LinearProgressIndicator(
                    progress = { backup.percent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
        },
        confirmButton = {},
    )

    is SettingsViewModel.Backup.Done -> AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup saved") },
        text = {
            Text(
                buildString {
                    append("${backup.megabytes} MB, ")
                    append(
                        when (backup.photos) {
                            1 -> "1 photo"
                            else -> "${backup.photos} photos"
                        }
                    )
                    append(".")
                    // Only mentioned when it happened. A photo can disappear
                    // between being listed and being read, and saying nothing
                    // would leave the archive quietly short.
                    if (backup.skipped > 0) {
                        append(
                            when (backup.skipped) {
                                1 -> " One photo could not be read and was left out."
                                else -> " ${backup.skipped} photos could not be read and were left out."
                            }
                        )
                    }
                }
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )

    is SettingsViewModel.Backup.Failed -> AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Couldn't save that backup") },
        text = { Text(backup.message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * What a restore has to say, at each of its four points.
 *
 * The confirmation is not a formality: it is the last moment before the wardrobe
 * on this device stops existing, so it says that plainly. The running dialog
 * cannot be dismissed, because there is nothing useful to do with a half-finished
 * restore except wait for it -- and the work continues whether the dialog is
 * there or not.
 */
@Composable
private fun RestoreDialog(
    restore: SettingsViewModel.Restore,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) = when (restore) {
    is SettingsViewModel.Restore.Confirming -> AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore from a backup?") },
        text = {
            Text(
                "Everything in this app's wardrobe will be replaced by the contents " +
                    "of the backup you pick. If the backup turns out not to be usable, " +
                    "nothing is changed."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Pick a backup") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    is SettingsViewModel.Restore.Running -> AlertDialog(
        onDismissRequest = {},
        title = { Text("Restoring") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text("Unpacking and checking the backup.", modifier = Modifier.padding(start = 16.dp))
            }
        },
        confirmButton = {},
    )

    is SettingsViewModel.Restore.Done -> AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wardrobe restored") },
        text = {
            Text(
                when (restore.garments) {
                    null -> "The backup was restored."
                    1L -> "The backup was restored: 1 garment."
                    else -> "The backup was restored: ${restore.garments} garments."
                }
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )

    is SettingsViewModel.Restore.Failed -> AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Couldn't restore that backup") },
        // The message is the whole point: it says whether to update the app,
        // find a different file, or that nothing was lost.
        text = { Text(restore.message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
