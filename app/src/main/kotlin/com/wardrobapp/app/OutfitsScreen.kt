package com.wardrobapp.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.OutfitRecord
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import com.wardrobapp.presentation.occasionChips
import com.wardrobapp.presentation.seasonChips
import kotlinx.coroutines.delay

/** The "building around this garment" banner, for a test that asks whether it is there. */
const val OUTFIT_SEED = "outfit-seed"

/** The show/hide control for outfits that were rated but not kept. */
const val OUTFIT_ARCHIVE_TOGGLE = "outfit-archive-toggle"

/** The line under a suggestion saying why it came up. */
/** The way to building an outfit by hand. */
const val OUTFIT_BUILD_ACTION = "outfit-build-action"

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
@OptIn(ExperimentalMaterial3Api::class)
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
    onBuildRequested: () -> Unit,
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.outfits_title)) },
                actions = {
                    // The way to an outfit the engine had no part in. In the bar
                    // rather than as a floating button: this list ends in buttons of
                    // its own, and one floating over them would cover the last.
                    IconButton(onClick = onBuildRequested, modifier = Modifier.testTag(OUTFIT_BUILD_ACTION)) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.outfit_build),
                        )
                    }
                },
            )
        },
    ) { insets ->
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

            item { SuggestButton(state, onGenerate) }

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

            itemsIndexed(state.suggestions, key = { _, it -> "suggestion-${it.id}" }) { index, suggestion ->
                // Three cards that land one at a time rather than three that are
                // suddenly there. Tapping suggest twice otherwise produces a list
                // that changes without appearing to move, and there is no way to
                // tell a fresh batch from the one already on screen.
                Arriving(index = index, key = suggestion.id) {
                    SuggestionCard(
                        suggestion = suggestion,
                        onSave = { onSave(suggestion) },
                        onRate = { rating -> onRate(suggestion, rating) },
                        onGarmentOpened = onGarmentOpened,
                    )
                }
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

/** How many garments a suggestion card draws across. */
private const val THUMBNAILS_ACROSS = 4

/** One chip, ready to draw: its label, whether it is on, and what it does. */
private data class Chip(val label: String, val active: Boolean, val onTap: () -> Unit)

/**
 * A row of choices: a heading, then one line you scroll sideways.
 *
 * A wrapping row was what this was, and on a wardrobe with every occasion in it
 * the two rows here took four lines between them -- so the button they exist to
 * narrow was off the bottom of the screen before anything had been suggested.
 * One line each keeps both headings and the button in view at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChipRow(label: String, chips: List<Chip>) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (chip in chips) {
                FilterChip(
                    selected = chip.active,
                    onClick = chip.onTap,
                    label = { Text(chip.label) },
                    shape = RoundedCornerShape(8.dp),
                )
            }
        }
    }
}

/**
 * A suggestion, landing.
 *
 * The resting transform is the real one and a flag flips a frame after the card
 * is composed, rather than the card being drawn small and then keyframed into
 * place. The difference matters when the animation never runs -- a phone with
 * animations turned off, or a screenshot test -- because a keyframe leaves the
 * card pinned at its starting transform and this leaves it where it belongs.
 *
 * [key] is the suggestion's id so a fresh batch re-runs the arrival: the whole
 * point is that a second tap on Suggest looks like something happened.
 */
@Composable
private fun Arriving(index: Int, key: Any, content: @Composable () -> Unit) {
    var landed by remember(key) { mutableStateOf(false) }

    LaunchedEffect(key) {
        delay(index * ARRIVAL_STAGGER_MILLIS)
        landed = true
    }

    val progress by animateFloatAsState(
        targetValue = if (landed) 1f else 0f,
        animationSpec = springGentle(),
        label = "arrival",
    )

    Box(
        modifier = Modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 18.dp.toPx()
            val scale = 0.96f + 0.04f * progress
            scaleX = scale
            scaleY = scale
        },
    ) { content() }
}

/** How far apart the cards land. The design's 120ms. */
private const val ARRIVAL_STAGGER_MILLIS = 120L

/**
 * Ask the engine, and ask it again.
 *
 * The label changes after the first press because the button is doing a different
 * thing by then: the first press fills an empty list, and every one after it
 * replaces three outfits you have just looked at. The glyph turns half a circle
 * per press, which is the only part of "these are new" that is visible while the
 * cards are still arriving.
 */
@Composable
private fun SuggestButton(state: OutfitsViewModel.State, onGenerate: () -> Unit) {
    var spins by remember { mutableStateOf(0) }
    val press = remember { MutableInteractionSource() }

    val angle by animateFloatAsState(
        targetValue = spins * 180f,
        animationSpec = springGentle(),
        label = "suggest-spin",
    )

    Button(
        onClick = {
            spins += 1
            onGenerate()
        },
        enabled = !state.generating,
        interactionSource = press,
        modifier = Modifier.fillMaxWidth().height(CTA_HEIGHT).pressScale(press),
    ) {
        Icon(
            Glyph.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = angle },
        )
        Text(
            stringResource(
                when {
                    state.generating -> R.string.outfits_suggesting
                    state.hasGenerated -> R.string.outfits_suggest_again
                    else -> R.string.outfits_suggest
                }
            ),
            style = ctaLabel(),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun SuggestionCard(
    suggestion: OutfitsViewModel.Suggestion,
    onSave: () -> Unit,
    onRate: (Int) -> Unit,
    onGarmentOpened: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    suggestion.outfit.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                // A bookmark that fills in rather than a word that becomes a
                // different word. Saved is a state of the card, and the filled
                // glyph is that state -- "Saved" as a label sat where the button
                // had been, which reads as the button having moved.
                IconButton(
                    onClick = onSave,
                    enabled = !suggestion.saved,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        if (suggestion.saved) Glyph.Bookmark else Glyph.BookmarkBorder,
                        contentDescription = stringResource(
                            if (suggestion.saved) R.string.outfit_saved_badge else R.string.action_save
                        ),
                        tint = if (suggestion.saved) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            // Why it came up. A score of 0.81 tells nobody anything; "you rated
            // these together" and "the colours work" are the parts of that number
            // worth reading, and they are what makes a suggestion arguable rather
            // than something to take on faith.
            if (suggestion.outfit.reasons.isNotEmpty()) {
                Text(
                    suggestion.outfit.reasons.map { stringResource(it.labelRes) }
                        .joinToString(" \u00b7 "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp).testTag(OUTFIT_REASONS),
                )
            }

            // Three to four and sharing the width, like every other frame in the
            // app that holds a garment. Square thumbs at a fixed 72dp cropped the
            // garment differently here than in the grid, so the same coat was two
            // different photos on two screens.
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            ) {
                for (garment in suggestion.outfit.garments) {
                    AsyncImage(
                        model = garment.displayImage,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(photoSurface())
                            .clickable { onGarmentOpened(garment.id) },
                    )
                }

                // A four-up row stays four-up on an outfit of three, so a
                // three-garment card does not draw wider photos than the card
                // above it.
                repeat(THUMBNAILS_ACROSS - suggestion.outfit.garments.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Stars(suggestion.rating, onRate)

                Box(modifier = Modifier.weight(1f))

                // What the row is for, said once and to the right of it, where the
                // save button used to be. The stars are five glyphs; nothing else
                // on the card says they are a question.
                Text(
                    stringResource(R.string.outfit_rate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

            // A pin is a toggle, and the glyph says which way it is set rather
            // than which state a tap reaches: the row is one of a list, and a
            // column of buttons reading "Pin / Unpin / Pin" is a column nobody
            // can scan. The description still says what the tap does.
            IconButton(onClick = onPinToggled, modifier = Modifier.size(40.dp)) {
                Icon(
                    Glyph.PushPin,
                    contentDescription = stringResource(
                        if (outfit.isPinned) R.string.action_unpin else R.string.action_pin
                    ),
                    tint = if (outfit.isPinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(
                    Glyph.DeleteOutline,
                    contentDescription = stringResource(R.string.outfit_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}


