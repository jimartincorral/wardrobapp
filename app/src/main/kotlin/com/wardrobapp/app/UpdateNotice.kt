package com.wardrobapp.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wardrobapp.data.AppRelease

/** The notice itself, for the tests that ask whether it is on screen. */
const val UPDATE_NOTICE = "update-notice"

/**
 * How many changelog lines are shown.
 *
 * A build published after a fortnight of merges has a long list, and a dialog is
 * not the place to read all of it. The newest ones are the ones that answer "what
 * will change if I install this".
 */
private const val CHANGES_SHOWN = 8

/**
 * "A newer build is out."
 *
 * A dialog rather than a banner because it carries a changelog and three answers,
 * and because installing is a thing you do deliberately. Shown at most once per
 * launch, and three ways out of it: install, not this build ever, or not now.
 *
 * While the download runs the dialog stays and shows how far it has got, with no
 * way to dismiss it -- backing out mid-download would leave a half-written APK and
 * a dialog claiming nothing was happening. It closes on its own when Android's
 * installer takes over.
 */
@Composable
fun UpdateNotice(
    state: UpdateViewModel.State,
    onInstall: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
    onFailureDismissed: () -> Unit,
) {
    val failure = state.failure
    if (failure != null) {
        AlertDialog(
            onDismissRequest = onFailureDismissed,
            title = { Text(stringResource(R.string.update_failed_title)) },
            text = { Text(failure) },
            confirmButton = {
                TextButton(onClick = onFailureDismissed) { Text(stringResource(R.string.action_close)) }
            },
        )
        return
    }

    val release = state.available ?: return

    AlertDialog(
        // Nothing while a download is running: the only way out of it is to let it
        // finish or fail, both of which end this dialog by themselves.
        onDismissRequest = { if (!state.downloading) onDismiss() },
        modifier = Modifier.testTag(UPDATE_NOTICE),
        title = { Text(stringResource(R.string.update_available_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    // The name as well as the build number, because the name is
                    // what Settings shows and the number is what makes it newer.
                    if (release.versionName.isBlank()) {
                        stringResource(R.string.update_build, release.versionCode)
                    } else {
                        stringResource(R.string.update_version, release.versionName, release.versionCode)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (release.changes.isNotEmpty()) {
                    Text(
                        stringResource(R.string.update_changes),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    for (change in release.changes.take(CHANGES_SHOWN)) {
                        Text(
                            "• $change",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (release.changes.size > CHANGES_SHOWN) {
                        Text(
                            stringResource(
                                R.string.update_changes_more,
                                release.changes.size - CHANGES_SHOWN,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                if (state.downloading) Downloading(state.progress)
            }
        },
        confirmButton = {
            // Absent rather than disabled while downloading: what it would do is
            // already happening, and the progress underneath says so.
            if (!state.downloading) {
                TextButton(onClick = onInstall) { Text(stringResource(R.string.update_install)) }
            }
        },
        dismissButton = {
            if (!state.downloading) {
                Row {
                    TextButton(onClick = onSkip) { Text(stringResource(R.string.update_skip)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) }
                }
            }
        },
    )
}

/** How far the download has got, or that it has started. */
@Composable
private fun Downloading(progress: Float?) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.update_downloading),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        // A bar only once there is something true to draw: a server that declares
        // no length would otherwise give a bar stuck at nothing.
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}
