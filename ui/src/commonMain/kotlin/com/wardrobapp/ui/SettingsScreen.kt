package com.wardrobapp.ui

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wardrobapp.data.ArchiveDetail
import com.wardrobapp.data.ArchivePreview
import com.wardrobapp.data.UnrestorableReason
import com.wardrobapp.presentation.LanguageChoice
import com.wardrobapp.presentation.SettingsScreenState
import com.wardrobapp.presentation.ThemeChoice
import com.wardrobapp.presentation.formatStoredDateTimeForReader
import com.wardrobapp.ui.resources.Res
import com.wardrobapp.ui.resources.action_cancel
import com.wardrobapp.ui.resources.action_close
import com.wardrobapp.ui.resources.action_done
import com.wardrobapp.ui.resources.action_retry
import com.wardrobapp.ui.resources.archive_archive_truncated
import com.wardrobapp.ui.resources.archive_backup_from_newer_app
import com.wardrobapp.ui.resources.archive_database_empty
import com.wardrobapp.ui.resources.archive_database_missing
import com.wardrobapp.ui.resources.archive_entry_outside_archive
import com.wardrobapp.ui.resources.archive_integrity_check_failed
import com.wardrobapp.ui.resources.archive_invalid_backup
import com.wardrobapp.ui.resources.archive_manifest_not_a_backup
import com.wardrobapp.ui.resources.archive_manifest_not_found
import com.wardrobapp.ui.resources.archive_manifest_unreadable
import com.wardrobapp.ui.resources.archive_manifest_version_missing
import com.wardrobapp.ui.resources.archive_no_database
import com.wardrobapp.ui.resources.archive_not_base64
import com.wardrobapp.ui.resources.archive_restore_failed
import com.wardrobapp.ui.resources.archive_rollback_failed
import com.wardrobapp.ui.resources.archive_unsupported_version
import com.wardrobapp.ui.resources.backup_done_title
import com.wardrobapp.ui.resources.backup_failed_title
import com.wardrobapp.ui.resources.backup_photos_skipped
import com.wardrobapp.ui.resources.backup_running_body
import com.wardrobapp.ui.resources.backup_running_title
import com.wardrobapp.ui.resources.error_wardrobe_unreadable
import com.wardrobapp.ui.resources.photo_count
import com.wardrobapp.ui.resources.restore_confirm_body
import com.wardrobapp.ui.resources.restore_confirm_title
import com.wardrobapp.ui.resources.restore_replaces_synced
import com.wardrobapp.ui.resources.restore_done_body
import com.wardrobapp.ui.resources.restore_done_garments
import com.wardrobapp.ui.resources.restore_done_title
import com.wardrobapp.ui.resources.restore_failed_title
import com.wardrobapp.ui.resources.restore_pick
import com.wardrobapp.ui.resources.restore_preview_made
import com.wardrobapp.ui.resources.restore_preview_photos
import com.wardrobapp.ui.resources.restore_preview_restore
import com.wardrobapp.ui.resources.restore_preview_settings
import com.wardrobapp.ui.resources.restore_preview_title
import com.wardrobapp.ui.resources.restore_preview_undated
import com.wardrobapp.ui.resources.restore_running_body
import com.wardrobapp.ui.resources.restore_running_title
import com.wardrobapp.ui.resources.settings_backup_create
import com.wardrobapp.ui.resources.settings_backup_hint
import com.wardrobapp.ui.resources.settings_backup_restore
import com.wardrobapp.ui.resources.settings_build
import com.wardrobapp.ui.resources.settings_garments
import com.wardrobapp.ui.resources.settings_language
import com.wardrobapp.ui.resources.settings_language_hint
import com.wardrobapp.ui.resources.settings_megabytes
import com.wardrobapp.ui.resources.settings_photos
import com.wardrobapp.ui.resources.settings_retired
import com.wardrobapp.ui.resources.settings_section_about
import com.wardrobapp.ui.resources.settings_section_backup
import com.wardrobapp.ui.resources.settings_section_cloud
import com.wardrobapp.ui.resources.settings_section_storage
import com.wardrobapp.ui.resources.settings_theme
import com.wardrobapp.ui.resources.settings_theme_hint
import com.wardrobapp.ui.resources.settings_tidy
import com.wardrobapp.ui.resources.settings_tidy_hint
import com.wardrobapp.ui.resources.settings_title
import com.wardrobapp.ui.resources.settings_version
import com.wardrobapp.ui.resources.tidy_done_body
import com.wardrobapp.ui.resources.tidy_done_title
import com.wardrobapp.ui.resources.tidy_failed_title
import com.wardrobapp.ui.resources.tidy_nothing_body
import com.wardrobapp.ui.resources.tidy_nothing_title
import com.wardrobapp.ui.resources.tidy_reclaimed_body
import com.wardrobapp.ui.resources.tidy_running_body
import com.wardrobapp.ui.resources.tidy_running_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Settings.
 *
 * Layout only. What the storage figures read as and how full the backup bar is
 * were decided in :presentation before anything reached here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsScreenState,
    version: AppVersion,
    /**
     * The language in force, read from the platform rather than from this app's
     * own state: AppCompat owns the choice, and a copy of it here could disagree
     * with what Android's per-app language screen shows.
     */
    language: LanguageChoice,
    /**
     * Null where the platform chooses the language and there is nothing for this
     * app to override: the browser, which reads it from the browser's own
     * settings. The section is left out rather than shown doing nothing.
     */
    onLanguageSelected: ((LanguageChoice) -> Unit)?,
    /**
     * The colours in force. Unlike the language this app stores it itself, since
     * Android has no per-app equivalent of the language screen to defer to.
     */
    theme: ThemeChoice,
    onThemeSelected: (ThemeChoice) -> Unit,
    /**
     * Null where this app does not make its own backups, and the section is left
     * out: in Home Assistant the wardrobe is part of the app's data, which Home
     * Assistant's own backups already include.
     */
    onBackupRequested: (() -> Unit)?,
    onBackupDismissed: () -> Unit,
    onRestoreRequested: () -> Unit,
    /** Agreed to restore in principle: opens the file picker. */
    onRestoreConfirmed: () -> Unit,
    /** Agreed to restore *this* archive, having been shown what is in it. */
    onArchiveConfirmed: (withSettings: Boolean) -> Unit,
    onRestoreDismissed: () -> Unit,
    /** Null where there is nothing to tidy with -- the server keeps photos as it was sent them. */
    onTidyRequested: (() -> Unit)?,
    onTidyDismissed: () -> Unit,
    onRetry: () -> Unit,
    /**
     * The Google Drive part of backing up, supplied rather than built here.
     *
     * A slot because this screen does not need to know what is in it: cloud backup
     * has its own state, its own failures and its own model, and threading six more
     * callbacks through here would make this signature about Drive rather than
     * about settings. Null where there is no cloud to back up to, and the section is
     * left out.
     */
    cloudSection: (@Composable () -> Unit)?,
    /**
     * Syncing with Home Assistant, supplied for the same reason the cloud
     * section is: the browser shows the pairing code and the phone asks for it,
     * and each has its own state for it. Heading included. Null to leave it out.
     */
    syncSection: (@Composable () -> Unit)? = null,
    /**
     * Which of several wardrobes this is, and switching between them: the
     * browser's, where one Home Assistant app holds a profile per person.
     * First, since everything below it is about the profile it names. Heading
     * included; null on the phone, which holds one wardrobe.
     */
    profileSection: (@Composable () -> Unit)? = null,
    /** Whether a restore here also replaces the wardrobe in Home Assistant; see RestoreDialog. */
    restoreReplacesSynced: Boolean = false,
) {
    state.backup?.let { backup ->
        BackupDialog(backup, onBackupDismissed)
    }
    state.restore?.let { restore ->
        RestoreDialog(restore, onRestoreConfirmed, onArchiveConfirmed, onRestoreDismissed, restoreReplacesSynced)
    }
    state.tidy?.let { tidy ->
        TidyDialog(tidy, onTidyDismissed)
    }

    Scaffold(
        topBar = {
            // No back arrow: this is a tab now, and the four beside it have none
            // either. An arrow here would offer to leave a place you did not
            // arrive at from anywhere.
            TopAppBar(title = { Text(stringResource(Res.string.settings_title)) })
        },
    ) { insets ->
        // Bound once rather than smart-cast through the branches below, which is
        // how the other screens read it too.
        val view = state.view

        // Each section on its own, so the two layouts below arrange the same
        // sections rather than each keeping a copy: the phone stacks them with
        // rules between, the desktop puts each on a card. Null where the section
        // is left out on this platform, as before.
        val storage: @Composable () -> Unit = {
            Section(stringResource(Res.string.settings_section_storage))
            when {
                view != null -> {
                    Figure(stringResource(Res.string.settings_garments), view.garments.toString())
                    // Only when there is something to say: a wardrobe nobody has
                    // retired anything from does not need a row reading zero.
                    if (view.retired > 0) {
                        Figure(stringResource(Res.string.settings_retired), view.retired.toString())
                    }
                    Figure(
                        stringResource(Res.string.settings_photos),
                        stringResource(Res.string.settings_megabytes, view.photoMegabytes),
                    )
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
                    Text(stringResource(Res.string.error_wardrobe_unreadable), style = MaterialTheme.typography.bodyMedium)
                    state.error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
                }
        }

        if (onTidyRequested != null) {
            Text(
                stringResource(Res.string.settings_tidy_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedButton(
                onClick = onTidyRequested,
                enabled = state.tidy !is SettingsScreenState.Tidy.Running,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(stringResource(Res.string.settings_tidy))
            }
        }
        }

        val backup: (@Composable () -> Unit)? = if (onBackupRequested == null) null else {
            {
                Section(stringResource(Res.string.settings_section_backup))
                Text(
                    stringResource(Res.string.settings_backup_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onBackupRequested,
                    enabled = state.backup !is SettingsScreenState.Backup.Running,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Text(stringResource(Res.string.settings_backup_create))
                }
                OutlinedButton(
                    onClick = onRestoreRequested,
                    enabled = state.restore == null && state.backup !is SettingsScreenState.Backup.Running,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(stringResource(Res.string.settings_backup_restore))
                }
            }
        }

        // Directly under the backup section, because it answers the same
        // question: where a copy of this wardrobe goes.
        val cloud: (@Composable () -> Unit)? = if (cloudSection == null) null else {
            {
                Section(stringResource(Res.string.settings_section_cloud))
                cloudSection()
            }
        }

        val languages: (@Composable () -> Unit)? = if (onLanguageSelected == null) null else {
            {
                Section(stringResource(Res.string.settings_language))
                Text(
                    stringResource(Res.string.settings_language_hint),
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
            }
        }

        val themes: @Composable () -> Unit = {
            Section(stringResource(Res.string.settings_theme))
            Text(
                stringResource(Res.string.settings_theme_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                for (choice in ThemeChoice.entries) {
                    FilterChip(
                        selected = choice == theme,
                        onClick = { onThemeSelected(choice) },
                        label = { Text(stringResource(choice.labelRes)) },
                    )
                }
        }
        }

        val about: @Composable () -> Unit = {
            Section(stringResource(Res.string.settings_section_about))
            // Read from the installed package rather than written here. The
            // React Native app hardcodes its version string, which means it has
            // been reporting 1.0.0 for every build it ever shipped.
            Figure(stringResource(Res.string.settings_version), version.name)
            Figure(stringResource(Res.string.settings_build), version.code.toString())
        }

        if (isExpanded()) {
            ExpandedSettings(
                insets = insets,
                left = listOfNotNull(profileSection, syncSection, backup, cloud),
                right = listOfNotNull(storage, languages, themes, about),
            )
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (profileSection != null) {
                profileSection()
                HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
            }

            storage()

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (backup != null) {
                backup()

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            // Beside the backups, because it answers the question they do --
            // where else this wardrobe is kept -- and before Drive, which is
            // the phone's alone.
            if (syncSection != null) {
                syncSection()

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            if (cloud != null) {
                cloud()

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            if (languages != null) {
                languages()

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            themes()

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            about()

            // Room to scroll clear of the gesture area at the bottom.
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Settings on a desktop-width window: every section on a card, in two columns.
 *
 * One page still, with no tabs and no list of sections to pick from -- there are
 * at most seven, and on a monitor they all fit. The left column is about which
 * wardrobe this is and where else it lives; the right is about this browser and
 * this build. The rules between sections on the phone are dropped, since the
 * cards already separate them.
 */
@Composable
private fun ExpandedSettings(
    insets: PaddingValues,
    left: List<@Composable () -> Unit>,
    right: List<@Composable () -> Unit>,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(insets)
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
    ) {
        MaxWidth(1120.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                for (column in listOf(left, right)) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        for (section in column) SettingsCard(section)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 24.dp)) { content() }
    }
}

/** The app's own version, as the installed package reports it. */
data class AppVersion(val name: String, val code: Long)

/** A section's heading. Internal so the sections Settings is handed (see SyncSections) look like its own. */
@Composable
internal fun Section(title: String) {
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
    backup: SettingsScreenState.Backup,
    onDismiss: () -> Unit,
) = when (backup) {
    is SettingsScreenState.Backup.Running -> AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(Res.string.backup_running_title)) },
        text = {
            Column {
                Text(stringResource(Res.string.backup_running_body))
                LinearProgressIndicator(
                    progress = { backup.percent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
        },
        confirmButton = {},
    )

    is SettingsScreenState.Backup.Done -> AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.backup_done_title)) },
        text = {
            Text(
                buildString {
                    append(stringResource(Res.string.settings_megabytes, backup.megabytes))
                    append(", ")
                    append(pluralStringResource(Res.plurals.photo_count, backup.photos, backup.photos))
                    append(".")
                    // Only mentioned when it happened. A photo can disappear
                    // between being listed and being read, and saying nothing
                    // would leave the archive quietly short.
                    if (backup.skipped > 0) {
                        append(" ")
                        append(
                            pluralStringResource(
                                Res.plurals.backup_photos_skipped,
                                backup.skipped,
                                backup.skipped,
                            )
                        )
                    }
                }
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_done)) } },
    )

    is SettingsScreenState.Backup.Failed -> AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.backup_failed_title)) },
        text = { Text(backup.message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) } },
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
/** The checkbox deciding whether an archive's settings come along. */
const val RESTORE_WITH_SETTINGS = "restore-with-settings"

/**
 * What an archive holds, before anything is replaced.
 *
 * Its own composable rather than a branch of [RestoreDialog], because it is the
 * only one of them with a decision in it: whether the archive's settings come
 * along. A `when` expression has nowhere to remember that.
 */
@Composable
private fun RestorePreviewDialog(
    preview: ArchivePreview,
    onConfirmRestore: (withSettings: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var withSettings by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.restore_preview_title)) },
        text = {
            Column {
                Text(
                    preview.createdAt?.let { made ->
                        stringResource(
                            Res.string.restore_preview_made,
                            formatStoredDateTimeForReader(made),
                        )
                    } ?: stringResource(Res.string.restore_preview_undated),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    pluralStringResource(
                        Res.plurals.restore_preview_photos,
                        preview.presentImages,
                        preview.presentImages,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                // Offered only when the archive has any, so the question is never
                // asked where it has one answer. Off to begin with: somebody
                // restoring is usually recovering from something going wrong, and
                // the wardrobe is what they came for -- changing their theme and
                // their backup schedule as well should be asked for rather than
                // arrived at by not reading a dialog.
                if (preview.hasSettings) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .testTag(RESTORE_WITH_SETTINGS)
                            .toggleable(
                                value = withSettings,
                                onValueChange = { withSettings = it },
                                role = Role.Checkbox,
                            ),
                    ) {
                        Checkbox(checked = withSettings, onCheckedChange = null)
                        Text(
                            stringResource(Res.string.restore_preview_settings),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                Text(
                    stringResource(Res.string.restore_confirm_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirmRestore(withSettings) }) {
                Text(stringResource(Res.string.restore_preview_restore))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

/**
 * Internal rather than private because the onboarding flow offers a restore too,
 * on its first screen, and the design asks for this path unchanged: the same
 * preview, the same confirmation, the same four dialogs. A second copy of them
 * would be a second place for the sentence about replacing a whole wardrobe to
 * drift.
 */
@Composable
fun RestoreDialog(
    restore: SettingsScreenState.Restore,
    /** Opens the file picker. */
    onConfirm: () -> Unit,
    /** Applies the archive already picked and described, with or without its settings. */
    onConfirmRestore: (withSettings: Boolean) -> Unit,
    onDismiss: () -> Unit,
    /**
     * Whether this phone syncs with Home Assistant, where a restore replaces
     * the wardrobe there and on every other phone too (PhoneSync.restoring)
     * -- which is worth saying before somebody picks a file, not after.
     */
    syncsWithHomeAssistant: Boolean = false,
) = when (restore) {
    is SettingsScreenState.Restore.Confirming -> AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.restore_confirm_title)) },
        text = {
            Column {
                Text(
                    stringResource(Res.string.restore_confirm_body)
                )
                if (syncsWithHomeAssistant) {
                    Text(
                        stringResource(Res.string.restore_replaces_synced),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(Res.string.restore_pick)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )

    /**
     * The archive that was picked, described before it is applied.
     *
     * The date carries the time of day: a rolling folder holds several backups and
     * two from the same afternoon are not told apart by "28 August". An archive
     * that never recorded one says so rather than showing a blank -- the older
     * formats did not have the field, and that is a fact about the backup rather
     * than a gap in the screen.
     */
    is SettingsScreenState.Restore.Previewing ->
        RestorePreviewDialog(restore.preview, onConfirmRestore, onDismiss)

    is SettingsScreenState.Restore.Running -> AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(Res.string.restore_running_title)) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(stringResource(Res.string.restore_running_body), modifier = Modifier.padding(start = 16.dp))
            }
        },
        confirmButton = {},
    )

    is SettingsScreenState.Restore.Done -> AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.restore_done_title)) },
        text = {
            Text(
                restore.garments?.let { count ->
                    pluralStringResource(Res.plurals.restore_done_garments, count.toInt(), count)
                } ?: stringResource(Res.string.restore_done_body)
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_done)) } },
    )

    is SettingsScreenState.Restore.Failed -> AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.restore_failed_title)) },
        // The message is the whole point: it says whether to update the app, find
        // a different file, or that nothing was lost. So it is the one place a
        // Spanish reader was still handed English, and now is not -- except for
        // the part of a sentence that was somebody else's words to begin with.
        text = {
            Text(
                restore.reason?.let { archiveFailureText(it) } ?: restore.message
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) } },
    )
}

/**
 * Why a backup would not restore, in the reader's language.
 *
 * The reasons nest -- a staged database that fails its integrity check produces
 * a fragment that the wrapping reason folds in -- so this and [detailText] call
 * each other, one level per fold.
 *
 * Composable, which it deliberately was not while the strings were Android's: it
 * was a plain function over a Context, one `getString` per level, because
 * recursion through `stringResource` had once broken the build. The strings are
 * Compose Multiplatform resources now, and those offer no lookup outside
 * composition that is not a suspend function, so the choice was between
 * recursion in composition and an asynchronous dialog text that arrives a frame
 * late. Recursion between composables is ordinary Compose; this was tried
 * again, and CI is what says whether it still breaks anything.
 *
 * The resource names match the case names by convention, which
 * `ArchiveMessageParityTest` relies on to hold each of these to the sentence
 * :data produces.
 */
@Composable
private fun archiveFailureText(reason: UnrestorableReason): String = when (reason) {
    is UnrestorableReason.ManifestUnreadable ->
        stringResource(Res.string.archive_manifest_unreadable, reason.name)

    is UnrestorableReason.ManifestNotABackup ->
        stringResource(Res.string.archive_manifest_not_a_backup, reason.name)

    is UnrestorableReason.ManifestVersionMissing ->
        stringResource(Res.string.archive_manifest_version_missing, reason.name)

    is UnrestorableReason.ManifestNotFound ->
        stringResource(Res.string.archive_manifest_not_found, reason.name)

    is UnrestorableReason.BackupFromNewerApp ->
        stringResource(Res.string.archive_backup_from_newer_app, reason.found, reason.supported)

    is UnrestorableReason.UnsupportedVersion ->
        stringResource(Res.string.archive_unsupported_version, reason.found, reason.readable)

    is UnrestorableReason.DatabaseMissing ->
        stringResource(Res.string.archive_database_missing, reason.name)

    is UnrestorableReason.DatabaseEmpty ->
        stringResource(Res.string.archive_database_empty, reason.name)

    UnrestorableReason.NoDatabase -> stringResource(Res.string.archive_no_database)

    is UnrestorableReason.ArchiveTruncated ->
        stringResource(Res.string.archive_archive_truncated, reason.expected, reason.present)

    UnrestorableReason.NotBase64 -> stringResource(Res.string.archive_not_base64)

    is UnrestorableReason.EntryOutsideArchive ->
        stringResource(Res.string.archive_entry_outside_archive, reason.entry)

    is UnrestorableReason.IntegrityCheckFailed ->
        stringResource(Res.string.archive_integrity_check_failed, reason.result)

    is UnrestorableReason.InvalidBackup ->
        stringResource(Res.string.archive_invalid_backup, detailText(reason.detail))

    is UnrestorableReason.RestoreFailed ->
        stringResource(Res.string.archive_restore_failed, detailText(reason.detail))

    is UnrestorableReason.RollbackFailed -> stringResource(
        Res.string.archive_rollback_failed,
        detailText(reason.detail),
        detailText(reason.rollbackDetail),
        reason.databaseName,
        reason.imagesName,
    )
}

/**
 * What a wrapping failure was caused by.
 *
 * [ArchiveDetail.Foreign] is returned as it came: SQLite's words, or the JDK's.
 * This app did not write them and cannot translate them, and dropping them would
 * leave a sentence with a hole where its only diagnostic was.
 */
@Composable
private fun detailText(detail: ArchiveDetail): String = when (detail) {
    is ArchiveDetail.Known -> archiveFailureText(detail.reason)
    is ArchiveDetail.Foreign -> detail.text
}

/**
 * What the photo tidy-up has to say.
 *
 * The running dialog cannot be dismissed, as the backup's cannot: the work carries
 * on either way, and every file it touches is one it is rewriting in place.
 *
 * "Nothing to optimize" is a separate answer rather than a saving of zero, because
 * it means something different -- the wardrobe is already as small as this app can
 * make it, which is the answer anyone running this twice should get.
 */
@Composable
private fun TidyDialog(
    tidy: SettingsScreenState.Tidy,
    onDismiss: () -> Unit,
) = when (tidy) {
    is SettingsScreenState.Tidy.Running -> AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(Res.string.tidy_running_title)) },
        text = {
            Column {
                Text(stringResource(Res.string.tidy_running_body, tidy.done, tidy.total))
                LinearProgressIndicator(
                    // Indeterminate until the total is known, which is only after
                    // the directory has been read: a bar sitting at zero because it
                    // has nothing to divide by reads as stuck.
                    progress = { if (tidy.total > 0) tidy.done / tidy.total.toFloat() else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
        },
        confirmButton = {},
    )

    is SettingsScreenState.Tidy.NothingToDo -> AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.tidy_nothing_title)) },
        text = {
            Text(pluralStringResource(Res.plurals.tidy_nothing_body, tidy.examined, tidy.examined))
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_done)) } },
    )

    is SettingsScreenState.Tidy.Done -> AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.tidy_done_title)) },
        text = {
            Column {
                Text(
                    pluralStringResource(
                        Res.plurals.tidy_done_body,
                        tidy.tidied,
                        tidy.tidied,
                        tidy.megabytes,
                    )
                )

                // Only when files were deleted, and said plainly: a pass that took
                // photos off the phone should not report it as "optimized".
                if (tidy.reclaimed > 0) {
                    Text(
                        pluralStringResource(
                            Res.plurals.tidy_reclaimed_body,
                            tidy.reclaimed,
                            tidy.reclaimed,
                        ),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_done)) } },
    )

    is SettingsScreenState.Tidy.Failed -> AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.tidy_failed_title)) },
        text = { Text(tidy.message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) } },
    )
}
