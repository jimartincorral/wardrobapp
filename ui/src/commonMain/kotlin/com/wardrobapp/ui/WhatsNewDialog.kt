package com.wardrobapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wardrobapp.data.ReleaseNote
import com.wardrobapp.data.ReleaseNoteKind
import com.wardrobapp.presentation.AppDestination
import com.wardrobapp.presentation.textIn
import com.wardrobapp.presentation.whatsNewGroups
import com.wardrobapp.ui.resources.Res
import com.wardrobapp.ui.resources.release_notes_language
import com.wardrobapp.ui.resources.whats_new_done
import com.wardrobapp.ui.resources.whats_new_fixed
import com.wardrobapp.ui.resources.whats_new_improved
import com.wardrobapp.ui.resources.whats_new_new
import com.wardrobapp.ui.resources.whats_new_show
import com.wardrobapp.ui.resources.whats_new_title
import org.jetbrains.compose.resources.stringResource

/** The dialog itself, for the tests that ask whether it is on screen. */
const val WHATS_NEW = "whats-new"

/**
 * The language release notes are shown in: the one the app's own strings
 * resolved to, so a note and the dialog around it are never in two languages
 * unless the note had no translation.
 */
@Composable
fun releaseNotesLanguage(): String = stringResource(Res.string.release_notes_language)

/**
 * What a build that was just installed changed, under New, Improved and Fixed.
 *
 * A dialog rather than a screen of its own: it is read once, after an update,
 * and the natural thing to do next is to get on with whatever the app was
 * opened for -- or, for a note that says where its change is, to go there,
 * which "Show me" does and closes this on the way. Either way out counts as
 * seen; the caller records that.
 */
@Composable
fun WhatsNewDialog(
    notes: List<ReleaseNote>,
    onShow: (AppDestination) -> Unit,
    onDismiss: () -> Unit,
) {
    val language = releaseNotesLanguage()

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(WHATS_NEW),
        title = { Text(stringResource(Res.string.whats_new_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                for ((index, group) in whatsNewGroups(notes).withIndex()) {
                    Text(
                        stringResource(
                            when (group.kind) {
                                ReleaseNoteKind.NEW -> Res.string.whats_new_new
                                ReleaseNoteKind.IMPROVED -> Res.string.whats_new_improved
                                ReleaseNoteKind.FIXED -> Res.string.whats_new_fixed
                            },
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 16.dp),
                    )
                    for (note in group.notes) {
                        Text(
                            "• ${note.textIn(language)}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        // Only a destination this build knows: one named by a
                        // later script would be a button that does nothing.
                        AppDestination.of(note.destination)?.let { destination ->
                            TextButton(onClick = { onShow(destination) }) {
                                Text(stringResource(Res.string.whats_new_show))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.whats_new_done)) }
        },
    )
}
