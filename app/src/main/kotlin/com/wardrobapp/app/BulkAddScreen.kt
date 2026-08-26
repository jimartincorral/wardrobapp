package com.wardrobapp.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
        Text(
            stringResource(R.string.bulk_add_progress, queue.position, queue.total),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(BULK_ADD_PROGRESS),
        )
    }

    item {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            AsyncImage(
                model = draft.displayUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(12.dp)),
            )

            // What was read off the photo, shown rather than asked about: a palette
            // worth arguing with is worth the garment's own form, and stopping to
            // argue is what this screen exists to avoid.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (hex in draft.colorPalette) {
                    hex.toComposeColor()?.let { ColorSwatch(it, size = 24.dp) }
                }
            }
        }
    }

    item {
        PhotoActions(
            hasCutout = draft.cutoutUri.isNotEmpty(),
            busy = saving,
            removingBackground = removingBackground,
            onCrop = onCrop,
            onRemoveBackground = onRemoveBackground,
            onUndoBackground = onUndoBackground,
        )
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
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                onClick = onSave,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().testTag(BULK_ADD_SAVE),
            ) {
                Text(stringResource(R.string.bulk_add_save))
            }

            TextButton(onClick = onSkip, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bulk_add_skip))
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
) {
    if (removingBackground) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.background_cutting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onCrop, enabled = !busy, modifier = Modifier.testTag(BULK_ADD_CROP)) {
            Text(stringResource(R.string.bulk_add_crop))
        }

        if (hasCutout) {
            TextButton(onClick = onUndoBackground, enabled = !busy) {
                Text(stringResource(R.string.background_undo))
            }
        } else {
            TextButton(onClick = onRemoveBackground, enabled = !busy) {
                Text(stringResource(R.string.background_remove))
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
