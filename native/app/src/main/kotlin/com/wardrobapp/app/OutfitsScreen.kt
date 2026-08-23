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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wardrobapp.data.OutfitRecord
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import com.wardrobapp.presentation.occasionChips
import com.wardrobapp.presentation.seasonChips

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
    onSave: (OutfitsViewModel.Suggestion) -> Unit,
    onRate: (OutfitsViewModel.Suggestion, Int) -> Unit,
    onPinToggled: (OutfitRecord) -> Unit,
    onDeleteRequested: (OutfitRecord) -> Unit,
    onDeleteConfirmed: () -> Unit,
    onDeleteDismissed: () -> Unit,
    onGarmentOpened: (String) -> Unit,
) {
    state.deleting?.let { outfit ->
        AlertDialog(
            onDismissRequest = onDeleteDismissed,
            title = { Text("Delete this outfit?") },
            // Named, because a prompt over a list of outfits that does not say
            // which one is a prompt nobody can answer safely. And it says what
            // survives: the garments are not going anywhere.
            text = {
                Text(
                    "\"${outfit.name}\" and its rating are deleted. The garments in it " +
                        "stay in your wardrobe."
                )
            },
            confirmButton = { TextButton(onClick = onDeleteConfirmed) { Text("Delete") } },
            dismissButton = { TextButton(onClick = onDeleteDismissed) { Text("Keep it") } },
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Outfits") }) }) { insets ->
        LazyColumn(
            modifier = Modifier.padding(insets),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Suggestions are built from what you own, and learn from what you rate.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                ChipRow("Season", state.filters.seasonChips().map { chip ->
                    Chip(chip.value?.label() ?: "Any", chip.active) { onSeasonTapped(chip.value) }
                })
            }

            item {
                ChipRow("Occasion", state.filters.occasionChips().map { chip ->
                    Chip(chip.value?.label() ?: "Any", chip.active) { onOccasionTapped(chip.value) }
                })
            }

            item {
                Button(
                    onClick = onGenerate,
                    enabled = !state.generating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.generating) "Building outfits…" else "Suggest outfits")
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
                            "No outfits could be built from what's available. " +
                                "Try fewer filters, or add a few more garments."
                        } else {
                            "Tap suggest to see what goes together."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (state.saved.isNotEmpty()) {
                item {
                    Text(
                        "Saved",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }

                items(state.saved, key = { "saved-${it.id}" }) { outfit ->
                    SavedOutfitRow(
                        outfit = outfit,
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
                        "Saved",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TextButton(onClick = onSave) { Text("Save") }
                }
            }
        }
    }
}

/**
 * Five stars.
 *
 * Text rather than icons: the extended icon set is not a dependency, and a star
 * is a character. It scales with the type size, which an icon would not.
 */
@Composable
private fun Stars(rating: Int?, onRate: (Int) -> Unit) {
    Row {
        for (star in 1..5) {
            val filled = rating != null && star <= rating

            Text(
                if (filled) "★" else "☆",
                style = MaterialTheme.typography.headlineSmall,
                color = if (filled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .clickable { onRate(star) }
                    .padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun SavedOutfitRow(
    outfit: OutfitRecord,
    onPinToggled: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
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
                    "${outfit.garmentIds.size} garments",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // A pin is a toggle, so it says which state tapping it reaches.
            TextButton(onClick = onPinToggled) {
                Text(if (outfit.isPinned) "Unpin" else "Pin")
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete this outfit",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun Season.label(): String =
    tag.replace('-', ' ').replaceFirstChar { it.uppercase() }

private fun Occasion.label(): String = id.replaceFirstChar { it.uppercase() }
