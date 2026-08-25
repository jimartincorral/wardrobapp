package com.wardrobapp.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.OutfitRecord
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import com.wardrobapp.presentation.occasionChips
import com.wardrobapp.presentation.seasonChips

/** The "building around this garment" banner, for a test that asks whether it is there. */
const val OUTFIT_SEED = "outfit-seed"

/** The show/hide control for outfits that were rated but not kept. */
const val OUTFIT_ARCHIVE_TOGGLE = "outfit-archive-toggle"

/** The line under a suggestion saying why it came up. */
const val OUTFIT_REASONS = "outfit-reasons"

/**
 * What every suggestion is being built around.
 *
 * Named and shown, with a way out of it. Without this the screen would quietly
 * keep answering a narrower question than the button appears to ask -- and
 * "Suggest outfits" returning three outfits that all contain the same coat, with
 * nothing saying why, reads as the engine being stuck.
 */
@Composable
private fun BuildingAround(seed: GarmentRecord, onCleared: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().testTag(OUTFIT_SEED)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The cut-out where there is one, which is what every other screen
            // shows a garment as.
            seed.displayImage.takeIf { it.isNotEmpty() }?.let { uri ->
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.outfits_building_around),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    seed.subcategory?.let { garmentTypeLabel(it) } ?: categoryLabel(seed.category),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            TextButton(onClick = onCleared) { Text(stringResource(R.string.outfits_use_whole_wardrobe)) }
        }
    }
}

/**
 * Outfit suggestions, and the ones that were kept.
 *
 * Layout only. Which chips are on, what the engine suggested and in what order
 * the saved outfits appear were all decided before anything reached here.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OutfitsScreen(
    state: OutfitsViewModel.State,
    onSeasonTapped: (Season?) -> Unit,
    onOccasionTapped: (Occasion?) -> Unit,
    onGenerate: () -> Unit,
    onSeedCleared: () -> Unit,
    onKeep: () -> Unit,
    onKeepDismissed: () -> Unit,
    onArchivedToggled: () -> Unit,
    onSave: (OutfitsViewModel.Suggestion) -> Unit,
    onRate: (OutfitsViewModel.Suggestion, Int) -> Unit,
    onPinToggled: (OutfitRecord) -> Unit,
    onDeleteRequested: (OutfitRecord) -> Unit,
    onDeleteConfirmed: () -> Unit,
    onDeleteDismissed: () -> Unit,
    onGarmentOpened: (String) -> Unit,
    onOutfitOpened: (String) -> Unit,
) {
    state.deleting?.let { outfit ->
        AlertDialog(
            onDismissRequest = onDeleteDismissed,
            title = { Text(stringResource(R.string.outfit_delete_confirm)) },
            // Named, because a prompt over a list of outfits that does not say
            // which one is a prompt nobody can answer safely. And it says what
            // survives: the garments are not going anywhere.
            text = {
                Text(
                    stringResource(R.string.outfit_delete_named_body, outfit.name)
                )
            },
            confirmButton = { TextButton(onClick = onDeleteConfirmed) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = onDeleteDismissed) { Text(stringResource(R.string.action_keep)) } },
        )
    }

    // The rating is already recorded and already learned from by the time this is
    // on screen, so there is no destructive answer here and no way to lose it: both
    // buttons and a dismiss all leave the rating exactly where it is.
    state.keeping?.let { rated ->
        AlertDialog(
            onDismissRequest = onKeepDismissed,
            title = { Text(stringResource(R.string.outfit_keep_title)) },
            text = { Text(stringResource(R.string.outfit_keep_body, rated.outfit.name)) },
            confirmButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.outfit_keep)) } },
            dismissButton = {
                TextButton(onClick = onKeepDismissed) { Text(stringResource(R.string.outfit_just_learn)) }
            },
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.outfits_title)) }) }) { insets ->
        LazyColumn(
            modifier = Modifier.padding(insets),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.outfits_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                ChipRow(stringResource(R.string.filter_section_season), state.filters.seasonChips().map { chip ->
                    val label = chip.value?.let { stringResource(it.labelRes) }
                        ?: stringResource(R.string.outfits_filter_any)
                    Chip(label, chip.active) { onSeasonTapped(chip.value) }
                })
            }

            item {
                ChipRow(stringResource(R.string.filter_section_occasion), state.filters.occasionChips().map { chip ->
                    val label = chip.value?.let { stringResource(it.labelRes) }
                        ?: stringResource(R.string.outfits_filter_any)
                    Chip(label, chip.active) { onOccasionTapped(chip.value) }
                })
            }

            // Above the button rather than below it, because it changes what the
            // button will do.
            state.seed?.let { seed ->
                item { BuildingAround(seed, onSeedCleared) }
            }

            item {
                Button(
                    onClick = onGenerate,
                    enabled = !state.generating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (state.generating) {
                                R.string.outfits_suggesting
                            } else {
                                R.string.outfits_suggest
                            }
                        )
                    )
                }
            }

            state.error?.let { error ->
                item {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (state.generating) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                    }
                }
            }

            items(state.suggestions, key = { "suggestion-${it.id}" }) { suggestion ->
                SuggestionCard(
                    suggestion = suggestion,
                    onSave = { onSave(suggestion) },
                    onRate = { rating -> onRate(suggestion, rating) },
                    onGarmentOpened = onGarmentOpened,
                )
            }

            // "Nothing came back" and "nothing has been asked for" are different
            // things to say, and the second is the common one.
            if (!state.generating && state.suggestions.isEmpty()) {
                item {
                    Text(
                        if (state.hasGenerated) {
                            stringResource(R.string.outfits_none_possible)
                        } else {
                            stringResource(R.string.outfits_prompt)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (state.saved.isNotEmpty() || state.archivedCount > 0) {
                item {
                    Text(
                        stringResource(R.string.outfits_saved_section),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }

                // Offered only once there is something behind it, and it says how
                // many: a toggle that reveals nothing is a toggle that looks
                // broken.
                if (state.archivedCount > 0) {
                    item {
                        TextButton(
                            onClick = onArchivedToggled,
                            modifier = Modifier.testTag(OUTFIT_ARCHIVE_TOGGLE),
                        ) {
                            Text(
                                if (state.showingArchived) {
                                    stringResource(R.string.outfits_hide_rated)
                                } else {
                                    pluralStringResource(
                                        R.plurals.outfits_show_rated,
                                        state.archivedCount.toInt(),
                                        state.archivedCount.toInt(),
                                    )
                                }
                            )
                        }
                    }
                }

                items(state.saved, key = { "saved-${it.id}" }) { outfit ->
                    SavedOutfitRow(
                        outfit = outfit,
                        onOpened = { onOutfitOpened(outfit.id) },
                        onPinToggled = { onPinToggled(outfit) },
                        onDelete = { onDeleteRequested(outfit) },
                    )
                }
            }
        }
    }
}

/** One chip, ready to draw: its label, whether it is on, and what it does. */
private data class Chip(val label: String, val active: Boolean, val onTap: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(label: String, chips: List<Chip>) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (chip in chips) {
                FilterChip(
                    selected = chip.active,
                    onClick = chip.onTap,
                    label = { Text(chip.label) },
                )
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    suggestion: OutfitsViewModel.Suggestion,
    onSave: () -> Unit,
    onRate: (Int) -> Unit,
    onGarmentOpened: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(suggestion.outfit.name, style = MaterialTheme.typography.titleMedium)

            // Why it came up. A score of 0.81 tells nobody anything; "you rated
            // these together" and "the colours work" are the parts of that number
            // worth reading, and they are what makes a suggestion arguable rather
            // than something to take on faith.
            if (suggestion.outfit.reasons.isNotEmpty()) {
                Text(
                    suggestion.outfit.reasons.joinToString(" \u00b7 ") { stringResource(it.labelRes) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp).testTag(OUTFIT_REASONS),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 12.dp),
            ) {
                for (garment in suggestion.outfit.garments) {
                    AsyncImage(
                        model = garment.displayImage,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onGarmentOpened(garment.id) },
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Stars(suggestion.rating, onRate)

                Box(modifier = Modifier.weight(1f))

                if (suggestion.saved) {
                    Text(
                        stringResource(R.string.outfit_saved_badge),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TextButton(onClick = onSave) { Text(stringResource(R.string.action_save)) }
                }
            }
        }
    }
}
@Composable
private fun SavedOutfitRow(
    outfit: OutfitRecord,
    onOpened: () -> Unit,
    onPinToggled: () -> Unit,
    onDelete: () -> Unit,
) {
    // The card opens the outfit; the two buttons on it do their own thing. The
    // clickable goes on the card rather than the row inside it so the whole
    // surface is the target, which is what a list of cards behaves like
    // everywhere else.
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpened)) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    outfit.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // Said on the row rather than left to the reader to infer from
                    // the toggle above: once the archived ones are shown they sit
                    // in the same list as the kept ones, and a row that is only
                    // here for what it taught should say so.
                    if (outfit.isArchived) {
                        stringResource(
                            R.string.outfit_rated_only,
                            pluralStringResource(
                                R.plurals.garment_count,
                                outfit.garmentIds.size,
                                outfit.garmentIds.size,
                            ),
                        )
                    } else {
                        pluralStringResource(
                            R.plurals.garment_count,
                            outfit.garmentIds.size,
                            outfit.garmentIds.size,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // A pin is a toggle, so it says which state tapping it reaches.
            TextButton(onClick = onPinToggled) {
                Text(
                    stringResource(if (outfit.isPinned) R.string.action_unpin else R.string.action_pin)
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.outfit_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}


