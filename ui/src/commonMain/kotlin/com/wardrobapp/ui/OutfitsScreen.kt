package com.wardrobapp.ui

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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.OutfitRecord
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import com.wardrobapp.presentation.OutfitsScreenState
import com.wardrobapp.presentation.occasionChips
import com.wardrobapp.presentation.seasonChips
import com.wardrobapp.ui.resources.Res
import com.wardrobapp.ui.resources.action_delete
import com.wardrobapp.ui.resources.action_keep
import com.wardrobapp.ui.resources.action_pin
import com.wardrobapp.ui.resources.action_save
import com.wardrobapp.ui.resources.action_unpin
import com.wardrobapp.ui.resources.filter_section_occasion
import com.wardrobapp.ui.resources.filter_section_season
import com.wardrobapp.ui.resources.garment_count
import com.wardrobapp.ui.resources.outfit_build
import com.wardrobapp.ui.resources.outfit_delete
import com.wardrobapp.ui.resources.outfit_delete_confirm
import com.wardrobapp.ui.resources.outfit_delete_named_body
import com.wardrobapp.ui.resources.outfit_just_learn
import com.wardrobapp.ui.resources.outfit_keep
import com.wardrobapp.ui.resources.outfit_keep_body
import com.wardrobapp.ui.resources.outfit_keep_title
import com.wardrobapp.ui.resources.outfit_rate
import com.wardrobapp.ui.resources.outfit_rated_only
import com.wardrobapp.ui.resources.outfit_saved_badge
import com.wardrobapp.ui.resources.outfits_building_around
import com.wardrobapp.ui.resources.outfits_filter_any
import com.wardrobapp.ui.resources.outfits_hide_rated
import com.wardrobapp.ui.resources.outfits_none_possible
import com.wardrobapp.ui.resources.outfits_prompt
import com.wardrobapp.ui.resources.outfits_saved_section
import com.wardrobapp.ui.resources.outfits_show_rated
import com.wardrobapp.ui.resources.outfits_subtitle
import com.wardrobapp.ui.resources.outfits_suggest
import com.wardrobapp.ui.resources.outfits_suggest_again
import com.wardrobapp.ui.resources.outfits_suggesting
import com.wardrobapp.ui.resources.outfits_title
import com.wardrobapp.ui.resources.taste_training_title
import com.wardrobapp.ui.resources.outfits_use_whole_wardrobe
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The "building around this garment" banner, for a test that asks whether it is there. */
const val OUTFIT_SEED = "outfit-seed"

/** The show/hide control for outfits that were rated but not kept. */
const val OUTFIT_ARCHIVE_TOGGLE = "outfit-archive-toggle"

/** The line under a suggestion saying why it came up. */
/** The way to building an outfit by hand. */
const val OUTFIT_BUILD_ACTION = "outfit-build-action"

/** The way into a training session, on the outfits screen. */
const val OUTFIT_TRAIN_ACTION = "outfit-train-action"

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
                    stringResource(Res.string.outfits_building_around),
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

            TextButton(onClick = onCleared) { Text(stringResource(Res.string.outfits_use_whole_wardrobe)) }
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
    state: OutfitsScreenState,
    onSeasonTapped: (Season?) -> Unit,
    onOccasionTapped: (Occasion?) -> Unit,
    onGenerate: () -> Unit,
    onSeedCleared: () -> Unit,
    onKeep: () -> Unit,
    onKeepDismissed: () -> Unit,
    onArchivedToggled: () -> Unit,
    onSave: (OutfitsScreenState.Suggestion) -> Unit,
    onRate: (OutfitsScreenState.Suggestion, Int) -> Unit,
    onPinToggled: (OutfitRecord) -> Unit,
    onDeleteRequested: (OutfitRecord) -> Unit,
    onDeleteConfirmed: () -> Unit,
    onDeleteDismissed: () -> Unit,
    onGarmentOpened: (String) -> Unit,
    onOutfitOpened: (String) -> Unit,
    onBuildRequested: () -> Unit,
    /** Open a training session: ten ideas rated in a row. See TasteTrainingScreen. */
    onTrainRequested: () -> Unit = {},
    /**
     * Photos of the garments in saved outfits, by garment id, for the desktop's
     * saved list. A saved outfit is stored as the ids of what is in it, and the
     * phone's row is words only; on a desktop there is room for the garments, and
     * whoever has them hands them over. Missing ones are simply not drawn.
     */
    garmentPhotos: Map<String, String> = emptyMap(),
) {
    state.deleting?.let { outfit ->
        AlertDialog(
            onDismissRequest = onDeleteDismissed,
            title = { Text(stringResource(Res.string.outfit_delete_confirm)) },
            // Named, because a prompt over a list of outfits that does not say
            // which one is a prompt nobody can answer safely. And it says what
            // survives: the garments are not going anywhere.
            text = {
                Text(
                    stringResource(Res.string.outfit_delete_named_body, outfit.name)
                )
            },
            confirmButton = { TextButton(onClick = onDeleteConfirmed) { Text(stringResource(Res.string.action_delete)) } },
            dismissButton = { TextButton(onClick = onDeleteDismissed) { Text(stringResource(Res.string.action_keep)) } },
        )
    }

    // The rating is already recorded and already learned from by the time this is
    // on screen, so there is no destructive answer here and no way to lose it: both
    // buttons and a dismiss all leave the rating exactly where it is.
    state.keeping?.let { rated -> KeepDialog(rated, onKeep, onKeepDismissed) }

    if (isExpanded()) {
        ExpandedOutfits(
            state = state,
            garmentPhotos = garmentPhotos,
            onSeasonTapped = onSeasonTapped,
            onOccasionTapped = onOccasionTapped,
            onGenerate = onGenerate,
            onSeedCleared = onSeedCleared,
            onArchivedToggled = onArchivedToggled,
            onSave = onSave,
            onRate = onRate,
            onPinToggled = onPinToggled,
            onDeleteRequested = onDeleteRequested,
            onGarmentOpened = onGarmentOpened,
            onOutfitOpened = onOutfitOpened,
            onBuildRequested = onBuildRequested,
            onTrainRequested = onTrainRequested,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.outfits_title)) },
                actions = {
                    // The way to an outfit the engine had no part in. In the bar
                    // rather than as a floating button: this list ends in buttons of
                    // its own, and one floating over them would cover the last.
                    IconButton(onClick = onBuildRequested, modifier = Modifier.testTag(OUTFIT_BUILD_ACTION)) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(Res.string.outfit_build),
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
                    stringResource(Res.string.outfits_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                ChipRow(stringResource(Res.string.filter_section_season), state.filters.seasonChips().map { chip ->
                    val label = chip.value?.let { stringResource(it.labelRes) }
                        ?: stringResource(Res.string.outfits_filter_any)
                    Chip(label, chip.active) { onSeasonTapped(chip.value) }
                })
            }

            item {
                ChipRow(stringResource(Res.string.filter_section_occasion), state.filters.occasionChips().map { chip ->
                    val label = chip.value?.let { stringResource(it.labelRes) }
                        ?: stringResource(Res.string.outfits_filter_any)
                    Chip(label, chip.active) { onOccasionTapped(chip.value) }
                })
            }

            // Above the button rather than below it, because it changes what the
            // button will do.
            state.seed?.let { seed ->
                item { BuildingAround(seed, onSeedCleared) }
            }

            item { SuggestButton(state, onGenerate, Modifier.fillMaxWidth()) }

            // Under the button that asks for ideas, because it is the way to
            // make those ideas better: a text button, since it is the second
            // thing here and the one most people will tap once.
            item { TrainButton(onTrainRequested, Modifier.fillMaxWidth()) }

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
                            stringResource(Res.string.outfits_none_possible)
                        } else {
                            stringResource(Res.string.outfits_prompt)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (state.saved.isNotEmpty() || state.archivedCount > 0) {
                item {
                    Text(
                        stringResource(Res.string.outfits_saved_section),
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
                                    stringResource(Res.string.outfits_hide_rated)
                                } else {
                                    pluralStringResource(
                                        Res.plurals.outfits_show_rated,
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

/**
 * How many garments a suggestion card draws across.
 *
 * Internal rather than private because the onboarding flow draws a picture of
 * this card, and the whole point of that picture is that it is the same width
 * arithmetic -- a four-up row on an outfit of three.
 */
const val THUMBNAILS_ACROSS = 4

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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(label: String, chips: List<Chip>, modifier: Modifier = Modifier, wrap: Boolean = false) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        val drawChips: @Composable () -> Unit = {
            for (chip in chips) {
                FilterChip(
                    selected = chip.active,
                    onClick = chip.onTap,
                    label = { Text(chip.label) },
                    shape = RoundedCornerShape(8.dp),
                )
            }
        }

        // Wrapping on a desktop, where the two rows sit side by side over a button
        // that is in view however many lines they take; see the comment above for
        // why the phone keeps them to one.
        if (wrap) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { drawChips() }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { drawChips() }
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

/** The way into a training session; see TasteTrainingScreen. */
@Composable
private fun TrainButton(onTrainRequested: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onTrainRequested, modifier = modifier.testTag(OUTFIT_TRAIN_ACTION)) {
        Icon(Glyph.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(Res.string.taste_training_title), modifier = Modifier.padding(start = 8.dp))
    }
}

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
private fun SuggestButton(
    state: OutfitsScreenState,
    onGenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        modifier = modifier.height(CTA_HEIGHT).pressScale(press),
    ) {
        Icon(
            Glyph.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = angle },
        )
        Text(
            stringResource(
                when {
                    state.generating -> Res.string.outfits_suggesting
                    state.hasGenerated -> Res.string.outfits_suggest_again
                    else -> Res.string.outfits_suggest
                }
            ),
            style = ctaLabel(),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
internal fun SuggestionCard(
    suggestion: OutfitsScreenState.Suggestion,
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
                            if (suggestion.saved) Res.string.outfit_saved_badge else Res.string.action_save
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
                    stringResource(Res.string.outfit_rate),
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
    /** The desktop's thumbnails, in outfit order; null on the phone, whose row is words only. */
    photos: List<String>? = null,
) {
    // The card opens the outfit; the two buttons on it do their own thing. The
    // clickable goes on the card rather than the row inside it so the whole
    // surface is the target, which is what a list of cards behaves like
    // everywhere else.
    Card(
        shape = if (photos == null) CardDefaults.shape else RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickCursor().clickable(onClick = onOpened),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // What is in it, small: a saved list of names reads as a list of names,
            // and the garments are what somebody scanning it remembers the outfit by.
            if (!photos.isNullOrEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(end = 12.dp),
                ) {
                    for (uri in photos.take(THUMBNAILS_ACROSS)) {
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(30.dp)
                                .aspectRatio(3f / 4f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(photoSurface()),
                        )
                    }
                }
            }

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
                            Res.string.outfit_rated_only,
                            pluralStringResource(
                                Res.plurals.garment_count,
                                outfit.garmentIds.size,
                                outfit.garmentIds.size,
                            ),
                        )
                    } else {
                        pluralStringResource(
                            Res.plurals.garment_count,
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
                        if (outfit.isPinned) Res.string.action_unpin else Res.string.action_pin
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
                    contentDescription = stringResource(Res.string.outfit_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * "Keep this one to wear?", after a rating.
 *
 * Shared by the outfits screen and the desktop's Home, which rates suggestions
 * from the same model and has to ask the same question afterwards.
 */
@Composable
private fun KeepDialog(
    rated: OutfitsScreenState.Suggestion,
    onKeep: () -> Unit,
    onKeepDismissed: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepDismissed,
        title = { Text(stringResource(Res.string.outfit_keep_title)) },
        text = { Text(stringResource(Res.string.outfit_keep_body, rated.outfit.name)) },
        confirmButton = { TextButton(onClick = onKeep) { Text(stringResource(Res.string.outfit_keep)) } },
        dismissButton = {
            TextButton(onClick = onKeepDismissed) { Text(stringResource(Res.string.outfit_just_learn)) }
        },
    )
}

/**
 * The first two suggestions, side by side: the desktop Home's "Outfit ideas".
 *
 * The same cards as the outfits screen, with the same bookmark and the same
 * stars, driven by an outfits model of Home's own -- so saving or rating one here
 * is the same request it is there, and the question after a rating is asked here
 * too rather than left waiting on a screen nobody is looking at.
 */
@Composable
fun OutfitIdeas(
    state: OutfitsScreenState,
    onSave: (OutfitsScreenState.Suggestion) -> Unit,
    onRate: (OutfitsScreenState.Suggestion, Int) -> Unit,
    onKeep: () -> Unit,
    onKeepDismissed: () -> Unit,
    onGarmentOpened: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    state.keeping?.let { rated -> KeepDialog(rated, onKeep, onKeepDismissed) }

    val shown = state.suggestions.take(HOME_IDEAS)

    when {
        shown.isNotEmpty() -> Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = modifier.fillMaxWidth(),
        ) {
            for ((index, suggestion) in shown.withIndex()) {
                Box(modifier = Modifier.weight(1f)) {
                    Arriving(index = index, key = suggestion.id) {
                        SuggestionCard(
                            suggestion = suggestion,
                            onSave = { onSave(suggestion) },
                            onRate = { rating -> onRate(suggestion, rating) },
                            onGarmentOpened = onGarmentOpened,
                        )
                    }
                }
            }
            // Half the width still, when only one came back, so it is the same card
            // it would have been beside a second.
            repeat(HOME_IDEAS - shown.size) { Spacer(modifier = Modifier.weight(1f)) }
        }

        state.generating -> Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.padding(24.dp))
        }

        else -> Text(
            stringResource(if (state.hasGenerated) Res.string.outfits_none_possible else Res.string.outfits_prompt),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}

/** How many ideas Home shows: one row of two, which is what fits beside the column of shortcuts. */
private const val HOME_IDEAS = 2

/**
 * Suggestions and saved outfits side by side, on a desktop-width window.
 *
 * Two columns rather than the phone's one list, because they are two different
 * things to do: the left asks the engine for something new, the right is what was
 * already kept. In one list the saved outfits were below every suggestion, so
 * the more suggestions there were, the further away the outfits you had already
 * chosen went. Each column scrolls on its own for the same reason.
 *
 * The suggestions are an auto-filling grid of the phone's cards rather than a
 * stretched column of them: a card wider than about 340dp makes the four photos
 * on it larger without showing anything more.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpandedOutfits(
    state: OutfitsScreenState,
    garmentPhotos: Map<String, String>,
    onSeasonTapped: (Season?) -> Unit,
    onOccasionTapped: (Occasion?) -> Unit,
    onGenerate: () -> Unit,
    onSeedCleared: () -> Unit,
    onArchivedToggled: () -> Unit,
    onSave: (OutfitsScreenState.Suggestion) -> Unit,
    onRate: (OutfitsScreenState.Suggestion, Int) -> Unit,
    onPinToggled: (OutfitRecord) -> Unit,
    onDeleteRequested: (OutfitRecord) -> Unit,
    onGarmentOpened: (String) -> Unit,
    onOutfitOpened: (String) -> Unit,
    onBuildRequested: () -> Unit,
    onTrainRequested: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.outfits_title)) },
                actions = {
                    // Spelled out: the phone's bare plus has the bar to itself, and
                    // here it would sit at the far end of a monitor-wide bar with
                    // nothing near it to say what it adds.
                    TextButton(onClick = onBuildRequested, modifier = Modifier.testTag(OUTFIT_BUILD_ACTION)) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(Res.string.outfit_build), modifier = Modifier.padding(start = 8.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                },
            )
        },
    ) { insets ->
        // Read out here: the grid's content block is not a composition.
        val seasons = state.filters.seasonChips().map { chip ->
            Chip(
                chip.value?.let { stringResource(it.labelRes) } ?: stringResource(Res.string.outfits_filter_any),
                chip.active,
            ) { onSeasonTapped(chip.value) }
        }
        val occasions = state.filters.occasionChips().map { chip ->
            Chip(
                chip.value?.let { stringResource(it.labelRes) } ?: stringResource(Res.string.outfits_filter_any),
                chip.active,
            ) { onOccasionTapped(chip.value) }
        }
        val seasonLabel = stringResource(Res.string.filter_section_season)
        val occasionLabel = stringResource(Res.string.filter_section_occasion)

        Row(
            modifier = Modifier.fillMaxSize().padding(insets).padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 340.dp),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                across {
                    Text(
                        stringResource(Res.string.outfits_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                across {
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        ChipRow(seasonLabel, seasons, modifier = Modifier.weight(1f), wrap = true)
                        ChipRow(occasionLabel, occasions, modifier = Modifier.weight(1f), wrap = true)
                    }
                }

                state.seed?.let { seed -> across { BuildingAround(seed, onSeedCleared) } }

                across {
                    // Let go of the grid's width first: a full-span item is handed
                    // exactly the grid's width as its minimum, and a cap below
                    // that minimum is ignored rather than obeyed.
                    SuggestButton(
                        state,
                        onGenerate,
                        modifier = Modifier.wrapContentWidth(Alignment.Start).widthIn(max = 360.dp).fillMaxWidth(),
                    )
                }

                across { TrainButton(onTrainRequested, Modifier.wrapContentWidth(Alignment.Start)) }

                state.error?.let { error ->
                    across {
                        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }

                if (state.generating) {
                    across {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                        }
                    }
                }

                itemsIndexed(state.suggestions, key = { _, it -> "suggestion-${it.id}" }) { index, suggestion ->
                    Arriving(index = index, key = suggestion.id) {
                        SuggestionCard(
                            suggestion = suggestion,
                            onSave = { onSave(suggestion) },
                            onRate = { rating -> onRate(suggestion, rating) },
                            onGarmentOpened = onGarmentOpened,
                        )
                    }
                }

                if (!state.generating && state.suggestions.isEmpty()) {
                    across {
                        Text(
                            stringResource(
                                if (state.hasGenerated) Res.string.outfits_none_possible else Res.string.outfits_prompt
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            Column(modifier = Modifier.width(400.dp).fillMaxHeight()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(Res.string.outfits_saved_section),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )

                    // As on the phone: offered only once there is something behind it.
                    if (state.archivedCount > 0) {
                        TextButton(onClick = onArchivedToggled, modifier = Modifier.testTag(OUTFIT_ARCHIVE_TOGGLE)) {
                            Text(
                                if (state.showingArchived) {
                                    stringResource(Res.string.outfits_hide_rated)
                                } else {
                                    pluralStringResource(
                                        Res.plurals.outfits_show_rated,
                                        state.archivedCount.toInt(),
                                        state.archivedCount.toInt(),
                                    )
                                }
                            )
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.saved, key = { "saved-${it.id}" }) { outfit ->
                        SavedOutfitRow(
                            outfit = outfit,
                            onOpened = { onOutfitOpened(outfit.id) },
                            onPinToggled = { onPinToggled(outfit) },
                            onDelete = { onDeleteRequested(outfit) },
                            photos = outfit.garmentIds.mapNotNull { garmentPhotos[it] },
                        )
                    }
                }
            }
        }
    }
}

/** One item across the whole grid, as the wardrobe's headers are. */
private fun LazyGridScope.across(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
