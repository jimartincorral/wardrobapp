package com.wardrobapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wardrobapp.presentation.BulkAddScreenState
import com.wardrobapp.presentation.BulkAddState
import com.wardrobapp.ui.resources.Res
import com.wardrobapp.ui.resources.action_back
import com.wardrobapp.ui.resources.action_close
import com.wardrobapp.ui.resources.bulk_add_choose
import com.wardrobapp.ui.resources.bulk_add_choose_more
import com.wardrobapp.ui.resources.bulk_add_done
import com.wardrobapp.ui.resources.bulk_add_finished
import com.wardrobapp.ui.resources.bulk_add_intro
import com.wardrobapp.ui.resources.bulk_add_progress
import com.wardrobapp.ui.resources.bulk_add_skipped
import com.wardrobapp.ui.resources.bulk_add_title
import com.wardrobapp.ui.resources.error_action_failed
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Tagged so a test can read the counter without matching a bare number twice. */
const val BULK_ADD_PROGRESS = "bulk-add-progress"

/** The button that writes the garment on screen and moves on. */
const val BULK_ADD_SAVE = "bulk-add-save"

/** What the batch came to, once the queue has drained. */
const val BULK_ADD_SUMMARY = "bulk-add-summary"

/** The re-crop offered per photo. */
const val BULK_ADD_CROP = "bulk-add-crop"

/**
 * Cataloguing several garments from several photos.
 *
 * Three faces: nothing picked yet, a garment being filled in, and a finished
 * batch. The middle one is not drawn here. It is the ordinary garment form,
 * with [GarmentBatch] around it, and the caller draws it through [form] because
 * the caller is what holds the form's model and wires every field of it -- the
 * same wiring the single form has, which is the whole point. This screen draws
 * the two faces on either side of it, and the one failure that belongs to the
 * queue rather than to any garment: photos that could not be copied in.
 *
 * [garment] is whatever identifies the garment on screen -- its form's model --
 * and the form is keyed on it. A new garment is then a new form in every way
 * the screen keeps for itself: scrolled back to the top rather than left at the
 * save button the last one was saved from, and with no half-typed tag carried
 * over from the garment before.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <G : Any> BulkAddScreen(
    state: BulkAddScreenState,
    garment: G?,
    onBack: () -> Unit,
    onChoosePhotos: () -> Unit,
    onErrorDismissed: () -> Unit,
    form: @Composable (G) -> Unit,
) {
    state.errorFallback?.let { reason ->
        AlertDialog(
            onDismissRequest = onErrorDismissed,
            title = { Text(stringResource(Res.string.error_action_failed)) },
            text = { Text(stringResource(reason.messageRes)) },
            confirmButton = {
                TextButton(onClick = onErrorDismissed) {
                    Text(stringResource(Res.string.action_close))
                }
            },
        )
    }

    // A garment first, even when more photos are still being copied in: the
    // first garment can be filled in while the rest arrive, which is the
    // difference between a queue and a wait.
    if (garment != null) {
        key(garment) { form(garment) }
        return
    }

    val queue = state.queue

    Scaffold(topBar = { BulkTopBar(onBack) }) { insets ->
        // One column on a phone and on a desktop alike, held to a reading width
        // there: with no photo to show beside them, these are a few lines and a
        // button or two, and a sentence stretched across a monitor is not one
        // anybody reads.
        Box(modifier = Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (queue.isFinished) {
                    finishedItems(queue, state.importing, onChoosePhotos, onBack)
                } else {
                    startItems(state.importing, onChoosePhotos)
                }
            }
        }
    }
}

/** Nothing picked yet: what this screen is for, and the way in. */
private fun LazyListScope.startItems(importing: Boolean, onChoosePhotos: () -> Unit) {
    item {
        Text(
            stringResource(Res.string.bulk_add_intro, BulkAddState.MAX_PHOTOS),
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    item {
        if (importing) {
            CircularProgressIndicator()
        } else {
            Button(onClick = onChoosePhotos, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.bulk_add_choose))
            }
        }
    }
}

/** The queue has drained: what it came to, and the two ways on from here. */
private fun LazyListScope.finishedItems(
    queue: BulkAddState,
    importing: Boolean,
    onChoosePhotos: () -> Unit,
    onBack: () -> Unit,
) {
    item {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.testTag(BULK_ADD_SUMMARY),
        ) {
            Text(
                pluralStringResource(Res.plurals.bulk_add_finished, queue.added, queue.added),
                style = MaterialTheme.typography.titleMedium,
            )

            // Only when there were any: "0 photos skipped" is a sentence about
            // nothing having happened.
            if (queue.skipped > 0) {
                Text(
                    pluralStringResource(Res.plurals.bulk_add_skipped, queue.skipped, queue.skipped),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    item {
        if (importing) {
            CircularProgressIndicator()
        } else {
            OutlinedButton(onClick = onChoosePhotos, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.bulk_add_choose_more))
            }
        }
    }

    item {
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.bulk_add_done))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BulkTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(Res.string.bulk_add_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.action_back),
                )
            }
        },
    )
}

/**
 * Where in the queue the garment on screen is, for the top of its form.
 *
 * "4 of 12" is a fact; the bar is how much is left, which is the thing somebody
 * halfway through a drawerful actually wants to know; and the strip is the
 * queue itself, so the drawerful visibly has an end.
 *
 * [onScreen] is the photo the form is showing, for the strip's current frame.
 * It comes from the form rather than the queue because the form owns it now --
 * cropped, cut out or replaced, the frame shows what the garment will be saved
 * with, not the photo as it was picked.
 */
@Composable
internal fun BatchProgress(queue: BulkAddState, onScreen: String?, wrap: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(Res.string.bulk_add_progress, queue.position, queue.total),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(BULK_ADD_PROGRESS),
        )

        LinearProgressIndicator(
            progress = { queue.done.toFloat() / queue.total.coerceAtLeast(1) },
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
            modifier = Modifier.fillMaxWidth().height(4.dp),
        )

        Filmstrip(queue, onScreen, wrap)
    }
}

/**
 * The queue, as a strip of frames, the one being worked on larger than the rest.
 *
 * The finished ones are drawn as empty frames rather than as their photos, and
 * that is a limit of the state rather than a choice: the queue holds what is
 * *left*, and a photo leaves it the moment its form opens. Dimmed to a third,
 * an empty frame reads as "done" -- which is what it is -- and keeping every
 * photo around to grey it out afterwards would mean holding a drawerful of
 * bitmaps to decorate a progress bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Filmstrip(queue: BulkAddState, onScreen: String?, wrap: Boolean) {
    val frames: @Composable () -> Unit = { Frames(queue, onScreen) }

    if (wrap) {
        // Wrapping on a desktop, where a column 400dp wide holds a dozen frames
        // a line and the whole drawerful is in view without a scroll.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(4.dp),
        ) { frames() }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) { frames() }
    }
}

/** The strip's frames, for whichever row holds them. */
@Composable
private fun Frames(queue: BulkAddState, onScreen: String?) {
    val done = queue.done

    repeat(queue.total) { index ->
        val current = index == done
        val frame = Modifier
            .width(30.dp)
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(4.dp))

        Box(
            modifier = if (current) {
                // The one on screen, a fifth again as large and outlined, so the
                // strip has a position in it and not just a length.
                frame
                    .graphicsLayer { scaleX = 1.22f; scaleY = 1.22f }
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
            } else {
                frame.alpha(if (index < done) 0.32f else 1f)
            }.background(photoSurface()),
        ) {
            val photo = when {
                index < done -> null
                current -> onScreen
                else -> queue.waiting.getOrNull(index - done - 1)
            }
            if (photo != null) {
                AsyncImage(
                    model = photo,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
