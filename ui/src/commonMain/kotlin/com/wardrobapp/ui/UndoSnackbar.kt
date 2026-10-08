package com.wardrobapp.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wardrobapp.presentation.Deleted
import com.wardrobapp.presentation.UndoOffer
import com.wardrobapp.ui.resources.Res
import com.wardrobapp.ui.resources.undo_action
import com.wardrobapp.ui.resources.undo_garment_deleted
import com.wardrobapp.ui.resources.undo_outfit_deleted
import org.jetbrains.compose.resources.stringResource

/**
 * The line that offers a delete back, for the app shell's snackbar slot.
 *
 * Drawn from the UndoHost's state rather than through Material's
 * SnackbarHostState, which owns its own clock and queue: the host already
 * decides how long an offer stands and what happens when it ends (see
 * UndoHost), and a second clock here would have the line and the offer
 * disagree about whether Undo still works. So this is the Snackbar
 * composable itself, shown while there is an offer and not otherwise, in
 * the slot Scaffold keeps above the bottom bar.
 *
 * No dismiss affordance beyond leaving it alone: the line goes by itself,
 * and a swipe would be one more gesture to explain for a few seconds'
 * difference.
 */
@Composable
fun UndoSnackbar(offer: UndoOffer?, onUndo: () -> Unit) {
    if (offer == null) return

    Snackbar(
        modifier = Modifier.padding(12.dp).testTag(UNDO_SNACKBAR),
        action = {
            TextButton(onClick = onUndo) { Text(stringResource(Res.string.undo_action)) }
        },
    ) {
        Text(
            stringResource(
                when (offer.deleted) {
                    Deleted.GARMENT -> Res.string.undo_garment_deleted
                    Deleted.OUTFIT -> Res.string.undo_outfit_deleted
                },
            ),
        )
    }
}

/** The undo line, for the tests. */
const val UNDO_SNACKBAR = "undo-snackbar"
