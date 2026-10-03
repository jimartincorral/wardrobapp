package com.wardrobapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import com.wardrobapp.ui.resources.Res
import com.wardrobapp.ui.resources.action_cancel
import com.wardrobapp.ui.resources.action_retry
import com.wardrobapp.ui.resources.action_save
import com.wardrobapp.ui.resources.profile_add
import com.wardrobapp.ui.resources.profile_choose_hint
import com.wardrobapp.ui.resources.profile_choose_title
import com.wardrobapp.ui.resources.profile_create
import com.wardrobapp.ui.resources.profile_is_yours
import com.wardrobapp.ui.resources.profile_load_failed
import com.wardrobapp.ui.resources.profile_make_yours
import com.wardrobapp.ui.resources.profile_name
import com.wardrobapp.ui.resources.profile_new
import com.wardrobapp.ui.resources.profile_rename
import com.wardrobapp.ui.resources.profile_rename_title
import com.wardrobapp.ui.resources.profile_showing
import com.wardrobapp.ui.resources.profile_switch
import com.wardrobapp.ui.resources.profile_switch_title
import com.wardrobapp.ui.resources.profile_unnamed
import com.wardrobapp.ui.resources.settings_section_profile
import org.jetbrains.compose.resources.stringResource

/*
 * Choosing between the wardrobes one Home Assistant app holds: the screen a
 * person meets the first time they open the app after it gained profiles, and
 * the section of Settings where they switch, rename and add them. Only the
 * browser shows either -- a phone holds one wardrobe, and syncs with the
 * profile whose code it was given.
 */

/** A profile, as these screens need it: what to ask for, and what to call it. */
data class ProfileEntry(val id: String, val name: String)

/** For the tests that look for the picker. */
const val PROFILE_PICKER = "profile-picker"

/**
 * What to call [profile]: its name, or -- for the wardrobe there was before
 * profiles, which nobody has named yet -- what it is, in the reader's language.
 */
@Composable
fun profileName(profile: ProfileEntry): String =
    profile.name.ifEmpty { stringResource(Res.string.profile_unnamed) }

/**
 * Whose wardrobe is this: each profile there is, to pick as one's own, and a
 * name to start a new one under.
 *
 * Shown instead of the app until a choice is made, because the alternative --
 * opening some wardrobe and letting somebody find the switch -- is how one
 * person ends up adding their clothes to another's. Shown once per person:
 * the choice is remembered by Home Assistant user.
 */
@Composable
fun ProfilePickerScreen(
    profiles: List<ProfileEntry>,
    failed: Boolean,
    onPick: (String) -> Unit,
    onCreate: (String) -> Unit,
    onRetry: () -> Unit,
) {
    var name by remember { mutableStateOf("") }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .widthIn(max = 480.dp)
                .testTag(PROFILE_PICKER),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.profile_choose_title), style = MaterialTheme.typography.headlineSmall)

            if (failed) {
                Text(stringResource(Res.string.profile_load_failed), color = MaterialTheme.colorScheme.error)
                Button(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
                return@Column
            }

            Text(
                stringResource(Res.string.profile_choose_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (profile in profiles) {
                OutlinedButton(onClick = { onPick(profile.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(profileName(profile))
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(stringResource(Res.string.profile_new), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.profile_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(Res.string.profile_create))
            }
        }
    }
}

/**
 * Settings' Wardrobe section: which profile is showing, whether it is the one
 * Home Assistant opens for this person, and switching, renaming and adding.
 *
 * At the top of Settings, because it changes what everything below it is
 * about: the storage figures, the pairing code, all of them are this
 * profile's. Heading included.
 */
@Composable
fun ProfileSection(
    current: ProfileEntry,
    profiles: List<ProfileEntry>,
    isYours: Boolean,
    signedIn: Boolean,
    onSwitch: (String) -> Unit,
    onRename: (String) -> Unit,
    onMakeYours: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var dialog by remember { mutableStateOf<ProfileDialog?>(null) }

    when (val shown = dialog) {
        ProfileDialog.Switch -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(Res.string.profile_switch_title)) },
            text = {
                Column {
                    for (profile in profiles.filter { it.id != current.id }) {
                        TextButton(onClick = {
                            dialog = null
                            onSwitch(profile.id)
                        }) { Text(profileName(profile)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialog = null }) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
        is ProfileDialog.Name -> NameDialog(
            title = stringResource(if (shown.renaming) Res.string.profile_rename_title else Res.string.profile_new),
            initial = if (shown.renaming) current.name else "",
            confirm = stringResource(if (shown.renaming) Res.string.action_save else Res.string.profile_create),
            onConfirm = { name ->
                dialog = null
                if (shown.renaming) onRename(name) else onCreate(name)
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }

    Section(stringResource(Res.string.settings_section_profile))
    Text(stringResource(Res.string.profile_showing, profileName(current)), style = MaterialTheme.typography.bodyLarge)

    if (isYours) {
        Text(
            stringResource(Res.string.profile_is_yours),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (signedIn) {
        TextButton(onClick = onMakeYours) { Text(stringResource(Res.string.profile_make_yours)) }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        if (profiles.size > 1) {
            OutlinedButton(onClick = { dialog = ProfileDialog.Switch }) { Text(stringResource(Res.string.profile_switch)) }
        }
        OutlinedButton(onClick = { dialog = ProfileDialog.Name(renaming = true) }) { Text(stringResource(Res.string.profile_rename)) }
        OutlinedButton(onClick = { dialog = ProfileDialog.Name(renaming = false) }) { Text(stringResource(Res.string.profile_add)) }
    }
}

private sealed interface ProfileDialog {
    data object Switch : ProfileDialog
    data class Name(val renaming: Boolean) : ProfileDialog
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.profile_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}
