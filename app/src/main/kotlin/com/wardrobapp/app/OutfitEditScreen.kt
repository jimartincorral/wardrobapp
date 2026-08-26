package com.wardrobapp.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.domain.GARMENT_CATEGORIES
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season

/** The list, so a test can scroll it: a lazy container has not composed the bottom. */
const val OUTFIT_EDIT_LIST = "outfit-edit-list"

/** The button that writes the outfit. */
const val OUTFIT_EDIT_SAVE = "outfit-edit-save"

/** One garment in the picker, by its id, so a tap can be aimed. */
fun outfitPickTag(garmentId: String) = "outfit-pick-$garmentId"

/**
 * Putting an outfit together, or changing one.
 *
 * The same screen for both: building starts from an empty outfit and ends in an
 * insert, editing starts from a stored one and ends in an update, and everything
 * between is identical. Two screens for that would be two screens that drift.
 *
 * The picker is the wardrobe grouped by category, because "what goes with this
 * shirt" is a question asked one category at a time -- and a flat list of two
 * hundred garments is one nobody scrolls to the end of.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutfitEditScreen(
    state: OutfitEditViewModel.State,
    isEditing: Boolean,
    onBack: () -> Unit,
    onNameChanged: (String) -> Unit,
    onGarmentToggled: (String) -> Unit,
    onOccasionTapped: (Occasion) -> Unit,
    onSeasonTapped: (Season) -> Unit,
    onSave: () -> Unit,
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
                title = {
                    Text(
                        stringResource(
                            if (isEditing) R.string.outfit_edit_title else R.string.outfit_build_title
                        )
                    )
                },
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
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize().padding(insets),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            // An outfit that has gone while its edit screen was being opened.
            state.missing -> Box(
                modifier = Modifier.fillMaxSize().padding(insets),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.outfit_missing)) }

            else -> Editor(
                state = state,
                insets = insets,
                onNameChanged = onNameChanged,
                onGarmentToggled = onGarmentToggled,
                onOccasionTapped = onOccasionTapped,
                onSeasonTapped = onSeasonTapped,
                onSave = onSave,
            )
        }
    }
}

@Composable
private fun Editor(
    state: OutfitEditViewModel.State,
    insets: PaddingValues,
    onNameChanged: (String) -> Unit,
    onGarmentToggled: (String) -> Unit,
    onOccasionTapped: (Occasion) -> Unit,
    onSeasonTapped: (Season) -> Unit,
    onSave: () -> Unit,
) {
    val edit = state.edit
    val chosen = edit.chosen(state.garments)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(insets).testTag(OUTFIT_EDIT_LIST),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                OutlinedTextField(
                    value = edit.name,
                    onValueChange = onNameChanged,
                    label = { Text(stringResource(R.string.outfit_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.outfit_name_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // What is in the outfit, before the wardrobe to pick from: this is the
        // thing being built, and it is what tells you whether the next tap is
        // adding to something or starting over.
        item {
            Section(stringResource(R.string.outfit_chosen)) {
                if (chosen.isEmpty()) {
                    Text(
                        stringResource(R.string.outfit_chosen_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(chosen) { garment ->
                            ChosenGarment(garment) { onGarmentToggled(garment.id) }
                        }
                    }
                }
            }
        }

        item {
            Section(stringResource(R.string.filter_section_occasion)) {
                Chips(
                    Occasion.entries.toList(),
                    setOfNotNull(edit.occasion),
                    { stringResource(it.labelRes) },
                ) { onOccasionTapped(it) }
            }
        }

        item {
            Section(stringResource(R.string.filter_section_season)) {
                Chips(
                    Season.entries.toList(),
                    setOfNotNull(edit.season),
                    { stringResource(it.labelRes) },
                ) { onSeasonTapped(it) }
            }
        }

        item {
            Button(
                onClick = onSave,
                enabled = edit.canSave && !state.saving,
                modifier = Modifier.fillMaxWidth().testTag(OUTFIT_EDIT_SAVE),
            ) {
                Text(stringResource(R.string.outfit_save))
            }
        }

        // The wardrobe to pick from, one heading per category. Only categories
        // something is in: an empty heading is a promise the wardrobe cannot keep.
        for (category in GARMENT_CATEGORIES) {
            val garments = state.garments.filter { it.category == category.id }
            if (garments.isEmpty()) continue

            item {
                Text(
                    categoryLabel(category.id),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(garments) { garment ->
                        PickableGarment(
                            garment = garment,
                            picked = edit.holds(garment.id),
                            onTap = { onGarmentToggled(garment.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A garment in the outfit, tappable to take it out.
 *
 * The same tap that added it removes it, rather than a separate delete: one
 * gesture to learn, and the picker below already shows the same garment as
 * chosen.
 */
@Composable
private fun ChosenGarment(garment: GarmentRecord, onTap: () -> Unit) {
    Column(modifier = Modifier.width(72.dp).clickable(onClick = onTap)) {
        AsyncImage(
            model = garment.displayImage,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(8.dp)),
        )
        Text(
            garmentTypeLabel(garment.subcategory ?: garment.category),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A garment in the wardrobe, with a tick when it is in the outfit. */
@Composable
private fun PickableGarment(garment: GarmentRecord, picked: Boolean, onTap: () -> Unit) {
    Column(
        modifier = Modifier
            .width(88.dp)
            .clickable(onClick = onTap)
            .testTag(outfitPickTag(garment.id)),
    ) {
        Box {
            AsyncImage(
                model = garment.displayImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(8.dp)),
            )

            // A tick rather than only a border: "which of these did I already
            // pick" has to be answerable at a glance, and a border reads as a
            // style rather than as a state.
            if (picked) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp),
                )
            }
        }

        Text(
            garmentTypeLabel(garment.subcategory ?: garment.category),
            style = MaterialTheme.typography.labelSmall,
            color = if (picked) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The exception's own words where there are any, the fallback otherwise. */
@Composable
private fun OutfitEditViewModel.State.errorText(): String? =
    error ?: errorFallback?.let { stringResource(it) }
