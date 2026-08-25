package com.wardrobapp.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.domain.GARMENT_CATEGORIES
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import com.wardrobapp.presentation.GARMENT_COLORS
import com.wardrobapp.presentation.GarmentSort
import com.wardrobapp.presentation.WardrobeQuery

/** The scrolling body of the wardrobe, for tests that need to reach past the fold. */
const val WARDROBE_LIST = "wardrobe-list"

/**
 * The wardrobe list.
 *
 * Layout only. What the list contains, in what order, and what its colours mean
 * were all decided before anything reached here.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WardrobeScreen(
    state: WardrobeViewModel.State,
    onSearchChanged: (String) -> Unit,
    onSortToggled: () -> Unit,
    onRetry: () -> Unit,
    onGarmentOpened: (String) -> Unit,
    onAddRequested: () -> Unit,
    onSettingsRequested: () -> Unit,
    onFiltersToggled: () -> Unit,
    onFiltersCleared: () -> Unit,
    onBrandChanged: (String) -> Unit,
    onSizeChanged: (String) -> Unit,
    onCategoryTapped: (String) -> Unit,
    onSubcategoryTapped: (String) -> Unit,
    onSeasonTapped: (Season) -> Unit,
    onOccasionTapped: (Occasion) -> Unit,
    onColorTapped: (String) -> Unit,
    onRetiredToggled: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wardrobe_title)) },
                actions = {
                    IconButton(onClick = onSettingsRequested) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddRequested) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.home_add_garment))
            }
        },
    ) { insets ->
        // Headers and garments in one list. The filter panel used to sit above the
        // list inside a plain Column, which measures the panel first and leaves the
        // list whatever is under it: on a phone the panel ran off the bottom of the
        // screen with its own last rows out of reach, and the list below it had no
        // room left to scroll in. As an item of the list, the panel scrolls with
        // everything else -- one gesture, the way the outfits screen and the garment
        // form already work.
        LazyColumn(
            // Tagged so a test can scroll it. A LazyColumn has not composed what is
            // below the fold, so "out of reach" and "not there at all" read the same
            // in an assertion unless the test can scroll first.
            modifier = Modifier.testTag(WARDROBE_LIST).fillMaxSize().padding(insets),
            // Room at the bottom so the add button does not sit on the last card.
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = state.query.search,
                        onValueChange = onSearchChanged,
                        label = { Text(stringResource(R.string.wardrobe_search)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onSortToggled) {
                        Text(
                            stringResource(
                                if (state.query.sort == GarmentSort.NEWEST) {
                                    R.string.sort_newest
                                } else {
                                    R.string.sort_oldest
                                }
                            )
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onFiltersToggled) {
                        // The count is the point: with the panel shut, it is the only
                        // sign that the list is not the whole wardrobe.
                        Text(
                            when {
                                state.filtersExpanded -> stringResource(R.string.filters_hide)
                                state.query.activeFilterCount > 0 -> stringResource(
                                    R.string.filters_show_count,
                                    state.query.activeFilterCount,
                                )
                                else -> stringResource(R.string.filters_show)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    if (state.query.isNarrowed) {
                        TextButton(onClick = onFiltersCleared) {
                            Text(stringResource(R.string.action_clear_filters))
                        }
                    }
                }
            }

            if (state.filtersExpanded) {
                item {
                    FilterPanel(
                        query = state.query,
                        onBrandChanged = onBrandChanged,
                        onSizeChanged = onSizeChanged,
                        onCategoryTapped = onCategoryTapped,
                        onSubcategoryTapped = onSubcategoryTapped,
                        onSeasonTapped = onSeasonTapped,
                        onOccasionTapped = onOccasionTapped,
                        onColorTapped = onColorTapped,
                        onRetiredToggled = onRetiredToggled,
                    )
                }
            }

            // Only once there is something to count. A count of zero is already
            // said, more usefully, by the empty state below.
            if (state.garments.isNotEmpty()) {
                item {
                    Text(
                        pluralStringResource(
                            R.plurals.garment_count,
                            state.garments.size,
                            state.garments.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            when {
                state.loading && state.garments.isEmpty() -> item {
                    Message { CircularProgressIndicator() }
                }

                // Reported, not swallowed. A read that failed must not look like
                // a wardrobe with nothing in it.
                state.error != null -> item {
                    Message {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stringResource(R.string.error_wardrobe_unreadable),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                state.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
                            )
                            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                        }
                    }
                }

                // Three different things, because they call for three different
                // next moves: wait, widen, or add something.
                state.isFilteredEmpty -> item {
                    Message {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                state.query.searchTerm?.let {
                                    stringResource(R.string.wardrobe_no_match_search, it)
                                } ?: stringResource(R.string.wardrobe_no_match_filters),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            TextButton(onClick = onFiltersCleared) {
                                Text(stringResource(R.string.action_clear_filters))
                            }
                        }
                    }
                }

                state.isEmpty -> item {
                    Message {
                        Text(stringResource(R.string.wardrobe_empty), style = MaterialTheme.typography.bodyLarge)
                    }
                }

                else -> items(state.garments, key = { it.id }) { garment ->
                    GarmentRow(
                        garment,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    ) { onGarmentOpened(garment.id) }
                }
            }
        }
    }
}

/**
 * The filter panel.
 *
 * Every dimension here was already ported and tested with nothing able to reach
 * it: `GarmentFilter` had six fields that no code ever set, and
 * `GarmentQueries.Filters` a seventh and an eighth. What is new is the way in.
 *
 * Subcategories appear only once a category is chosen, because they are its
 * subcategories -- offering all of them at once would let you filter by a type
 * the chosen category does not have and show nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(
    query: WardrobeQuery,
    onBrandChanged: (String) -> Unit,
    onSizeChanged: (String) -> Unit,
    onCategoryTapped: (String) -> Unit,
    onSubcategoryTapped: (String) -> Unit,
    onSeasonTapped: (Season) -> Unit,
    onOccasionTapped: (Occasion) -> Unit,
    onColorTapped: (String) -> Unit,
    onRetiredToggled: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query.brand,
                onValueChange = onBrandChanged,
                label = { Text(stringResource(R.string.filter_brand)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = query.size,
                onValueChange = onSizeChanged,
                label = { Text(stringResource(R.string.filter_size)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        FilterSection(stringResource(R.string.filter_section_category)) {
            for (category in GARMENT_CATEGORIES) {
                FilterPill(categoryLabel(category.id), query.category == category.id) {
                    onCategoryTapped(category.id)
                }
            }
        }

        GARMENT_CATEGORIES.firstOrNull { it.id == query.category }?.let { category ->
            FilterSection(stringResource(R.string.filter_section_type)) {
                for (subcategory in category.subcategories) {
                    FilterPill(garmentTypeLabel(subcategory), query.subcategory == subcategory) {
                        onSubcategoryTapped(subcategory)
                    }
                }
            }
        }

        FilterSection(stringResource(R.string.filter_section_season)) {
            for (season in Season.entries) {
                FilterPill(stringResource(season.labelRes), query.season == season) { onSeasonTapped(season) }
            }
        }

        FilterSection(stringResource(R.string.filter_section_occasion)) {
            for (occasion in Occasion.entries) {
                FilterPill(stringResource(occasion.labelRes), query.occasion == occasion) {
                    onOccasionTapped(occasion)
                }
            }
        }

        FilterSection(stringResource(R.string.filter_section_colour)) {
            for ((key, hex) in GARMENT_COLORS) {
                val selected = query.color == key
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(hex.toComposeColor() ?: MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape,
                        )
                        .clickable { onColorTapped(key) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = query.includeRetired, onCheckedChange = { onRetiredToggled() })
            Text(
                // The reason this exists: without it a retired garment cannot be
                // found again, so it cannot be un-retired either.
                stringResource(R.string.filter_include_retired),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(title: String, content: @Composable FlowRowScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onTap: () -> Unit) {
    FilterChip(selected = selected, onClick = onTap, label = { Text(label) })
}

// The three label helpers that used to live here are gone: Vocabulary.kt answers
// for seasons, occasions, categories and types now, out of resources.

/**
 * Something to say instead of a list: still loading, unreadable, or nothing in it.
 *
 * Across the width and under the controls rather than in the middle of the screen,
 * because the middle of a scrolling list is wherever the list happens to be. The
 * search box and the filters are what every one of these messages asks you to
 * change, so it sits directly under them.
 */
@Composable
private fun Message(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun GarmentRow(garment: GarmentRecord, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = garment.displayImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    // Through the vocabulary rather than shown as stored: the row
                    // held raw English -- "T-Shirt" -- whatever the language.
                    garment.subcategory?.let { garmentTypeLabel(it) }
                        ?: categoryLabel(garment.category),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                garment.brand?.takeIf { it.isNotBlank() }?.let { brand ->
                    Text(
                        brand,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                garment.tags.takeIf { it.isNotEmpty() }?.let { tags ->
                    Text(
                        tags.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // The palette, as swatches. An unparseable colour is left blank
            // rather than guessed at.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (hex in garment.palette.take(3)) {
                    val color = hex.toComposeColor()
                    if (color != null) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(color),
                        )
                    }
                }
            }

            garment.size?.takeIf { it.isNotBlank() }?.let { size ->
                Text(
                    size,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}
