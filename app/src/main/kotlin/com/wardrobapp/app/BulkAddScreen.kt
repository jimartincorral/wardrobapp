package com.wardrobapp.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wardrobapp.domain.GARMENT_CATEGORIES
import com.wardrobapp.domain.garmentCategory
import com.wardrobapp.presentation.BulkAddState

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
 * One screen with three faces: nothing picked yet, a photo waiting to be told
 * what it is, and a finished batch. Which one shows is a question about the
 * queue, answered in :presentation, rather than a mode this screen keeps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkAddScreen(
    state: BulkAddViewModel.State,
    onBack: () -> Unit,
    onChoosePhotos: () -> Unit,
    onCategorySelected: (String) -> Unit,
    onSubcategoryToggled: (String) -> Unit,
    onBrandChanged: (String) -> Unit,
    onCrop: () -> Unit,
    onRemoveBackground: () -> Unit,
    onUndoBackground: () -> Unit,
    onSave: () -> Unit,
    onSkip: () -> Unit,
    onErrorDismissed: () -> Unit,
) {
    state.errorText()?.let { error ->
        AlertDialog(
            onDismissRequest = onErrorDismissed,
            title = { Text(stringResource(R.string.error_action_failed)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = onErrorDismissed) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bulk_add_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { insets ->
        val queue = state.queue
        val draft = queue.current

        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(insets),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                // A draft first, even when more photos are still being copied in:
                // the first garment can be filled in while the rest arrive, which
                // is the difference between a queue and a wait.
                draft != null -> draftItems(
                    queue = queue,
                    draft = draft,
                    saving = state.saving,
                    removingBackground = state.removingBackground,
                    onCategorySelected = onCategorySelected,
                    onSubcategoryToggled = onSubcategoryToggled,
                    onBrandChanged = onBrandChanged,
                    onCrop = onCrop,
                    onRemoveBackground = onRemoveBackground,
                    onUndoBackground = onUndoBackground,
                    onSave = onSave,
                    onSkip = onSkip,
                )

                queue.isFinished -> finishedItems(queue, state.importing, onChoosePhotos, onBack)

                else -> startItems(state.importing, onChoosePhotos)
            }
        }
    }
}

/** Nothing picked yet: what this screen is for, and the way in. */
private fun LazyListScope.startItems(importing: Boolean, onChoosePhotos: () -> Unit) {
    item {
        Text(
            stringResource(R.string.bulk_add_intro, BulkAddState.MAX_PHOTOS),
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    item {
        if (importing) {
            CircularProgressIndicator()
        } else {
            Button(onClick = onChoosePhotos, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bulk_add_choose))
            }
        }
    }
}

/**
 * One photo, waiting to be told what it is.
 *
 * The category is what is asked for, because it is the thing the photo cannot
 * answer and the suggestion engine cannot do without. Type and brand sit next to
 * it since they are a tap each while the garment is in front of you.
 *
 * Cropping and background removal are offered here rather than deferred to the
 * garment's own form: they are what decides how the garment *looks* in every list
 * it will ever appear in, and a photo left as it came off the camera is the one
 * thing a later edit is least likely to go back and fix. Both are a tap, and
 * neither is required. What is left for the form is only what a photo cannot show
 * -- size, tags, a second photo.
 */
private fun LazyListScope.draftItems(
    queue: BulkAddState,
    draft: BulkAddState.Draft,
    /** A garment being written. Not "photos still arriving": those must not block a tap. */
    saving: Boolean,
    removingBackground: Boolean,
    onCategorySelected: (String) -> Unit,
    onSubcategoryToggled: (String) -> Unit,
    onBrandChanged: (String) -> Unit,
    onCrop: () -> Unit,
    onRemoveBackground: () -> Unit,
    onUndoBackground: () -> Unit,
    onSave: () -> Unit,
    onSkip: () -> Unit,
) {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.bulk_add_progress, queue.position, queue.total),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(BULK_ADD_PROGRESS),
            )

            // "4 of 12" is a fact; the bar is how much is left, which is the thing
            // somebody halfway through a drawerful actually wants to know.
            LinearProgressIndicator(
                progress = { (queue.position - 1).toFloat() / queue.total.coerceAtLeast(1) },
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )

            Filmstrip(queue)
        }
    }

    item {
        // Keyed on the photo, so advancing the queue slides the finished garment
        // out to the left and lands the next one from the right. Without it the
        // photo simply becomes a different photo, and the one thing this screen
        // has to make obvious -- that a garment was written and the queue moved --
        // is invisible.
        AnimatedContent(
            targetState = draft.displayUri,
            transitionSpec = {
                (slideInHorizontally(springGentle()) { it / 3 } + fadeIn(springGentle()))
                    .togetherWith(
                        slideOutHorizontally(springGentle()) { -(it * 7) / 10 } +
                            scaleOut(springGentle(), targetScale = 0.8f) +
                            fadeOut(springGentle())
                    )
            },
            label = "bulk-advance",
        ) { uri ->
            Box(modifier = Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(photoSurface()),
                )

                // On the photo rather than under it. These two act on the picture,
                // and the picture is the biggest thing on the screen: a row of
                // words below it read as belonging to the category chips.
                PhotoActions(
                    hasCutout = draft.cutoutUri.isNotEmpty(),
                    busy = saving,
                    removingBackground = removingBackground,
                    onCrop = onCrop,
                    onRemoveBackground = onRemoveBackground,
                    onUndoBackground = onUndoBackground,
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                )

                // What was read off the photo, shown rather than asked about: a
                // palette worth arguing with is worth the garment's own form, and
                // stopping to argue is what this screen exists to avoid.
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                ) {
                    for (hex in draft.colorPalette) {
                        hex.toComposeColor()?.let { ColorSwatch(it, size = 24.dp) }
                    }
                }
            }
        }
    }

    item {
        Section(stringResource(R.string.filter_section_category)) {
            Chips(GARMENT_CATEGORIES.map { it.id }, setOf(draft.category), { categoryLabel(it) }) {
                onCategorySelected(it)
            }
        }
    }

    garmentCategory(draft.category)?.let { category ->
        item {
            Section(stringResource(R.string.filter_section_type)) {
                Chips(
                    category.subcategories,
                    draft.subcategories.toSet(),
                    { garmentTypeLabel(it) },
                ) {
                    onSubcategoryToggled(it)
                }
            }
        }
    }

    item {
        OutlinedTextField(
            value = draft.brand,
            onValueChange = onBrandChanged,
            label = { Text(stringResource(R.string.bulk_add_brand)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    item {
        // Side by side, and different weights, because they are not two versions
        // of the same move: one writes a garment and one throws a photo away.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = onSkip,
                enabled = !saving,
                modifier = Modifier.height(44.dp),
            ) {
                Icon(Glyph.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.bulk_add_skip),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }

            val press = remember { MutableInteractionSource() }

            Button(
                onClick = onSave,
                enabled = !saving,
                interactionSource = press,
                modifier = Modifier
                    .weight(1f)
                    .height(CTA_HEIGHT)
                    .pressScale(press)
                    .testTag(BULK_ADD_SAVE),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(R.string.bulk_add_save),
                    style = ctaLabel(),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/**
 * The queue, as a strip of frames.
 *
 * The point is that a drawerful has an end. "4 of 12" says so in words; twelve
 * frames say so at a glance, with the one being worked on larger than the rest.
 *
 * The finished ones are drawn as empty frames rather than as their photos, and
 * that is a limit of the state rather than a choice: the queue holds what is
 * *left*, and a draft is dropped the moment it becomes a garment. Dimmed to a
 * third, an empty frame reads as "done" -- which is what it is -- and keeping
 * every photo alive to grey it out afterwards would mean holding a drawerful of
 * bitmaps to decorate a progress bar.
 */
@Composable
private fun Filmstrip(queue: BulkAddState) {
    val done = queue.position - 1

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        repeat(queue.total) { index ->
            val current = index == done
            val frame = Modifier
                .width(30.dp)
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(4.dp))

            Box(
                modifier = if (current) {
                    // The one on screen, a fifth again as large and outlined, so
                    // the strip has a position in it and not just a length.
                    frame
                        .graphicsLayer { scaleX = 1.22f; scaleY = 1.22f }
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                } else {
                    frame.alpha(if (index < done) 0.32f else 1f)
                }.background(photoSurface()),
            ) {
                queue.drafts.getOrNull(index - done)?.let { draft ->
                    AsyncImage(
                        model = draft.displayUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * What can be done to the photo before it becomes a garment.
 *
 * Undo replaces Remove once there is a cut-out, rather than sitting beside it: two
 * buttons where one applies is how somebody removes a background twice. Cropping
 * stays offered either way and clears the cut-out when used, because a cut-out of
 * the uncropped photo no longer describes what is there.
 */
@Composable
private fun PhotoActions(
    hasCutout: Boolean,
    busy: Boolean,
    removingBackground: Boolean,
    onCrop: () -> Unit,
    onRemoveBackground: () -> Unit,
    onUndoBackground: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (removingBackground) {
        GlassChip(modifier = modifier) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp))
            Text(
                stringResource(R.string.background_cutting),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        GlassChip(
            onClick = onCrop.takeIf { !busy },
            modifier = Modifier.testTag(BULK_ADD_CROP),
        ) {
            Icon(Glyph.Crop, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                stringResource(R.string.bulk_add_crop),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        GlassChip(
            onClick = (if (hasCutout) onUndoBackground else onRemoveBackground).takeIf { !busy },
        ) {
            Icon(Glyph.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                stringResource(
                    if (hasCutout) R.string.background_undo else R.string.background_remove
                ),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/**
 * A control that sits on top of a photo.
 *
 * Its own surface rather than a plain text button, because what is behind it is a
 * photograph: a label with no ground under it is legible over a white shirt and
 * gone over a black coat. `surfaceContainerHigh` at nine tenths keeps some of the
 * photo visible through it, which is what says the chip belongs to the picture
 * and not to the form below it.
 */
@Composable
private fun GlassChip(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = CircleShape,
        modifier = if (onClick == null) modifier else modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.height(36.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
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
                pluralStringResource(R.plurals.bulk_add_finished, queue.added, queue.added),
                style = MaterialTheme.typography.titleMedium,
            )

            // Only when there were any: "0 photos skipped" is a sentence about
            // nothing having happened.
            if (queue.skipped > 0) {
                Text(
                    pluralStringResource(R.plurals.bulk_add_skipped, queue.skipped, queue.skipped),
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
                Text(stringResource(R.string.bulk_add_choose_more))
            }
        }
    }

    item {
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.bulk_add_done))
        }
    }
}

/** The exception's own words where there are any, the fallback otherwise. */
@Composable
private fun BulkAddViewModel.State.errorText(): String? =
    error ?: errorFallback?.let { stringResource(it) }
