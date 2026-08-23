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
                title = { Text("Wardrobe") },
                actions = {
                    IconButton(onClick = onSettingsRequested) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddRequested) {
                Icon(Icons.Filled.Add, contentDescription = "Add a garment")
            }
        },
    ) { insets ->
        Column(modifier = Modifier.fillMaxSize().padding(insets)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.query.search,
                    onValueChange = onSearchChanged,
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSortToggled) {
                    Text(if (state.query.sort == GarmentSort.NEWEST) "Newest" else "Oldest")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onFiltersToggled) {
                    // The count is the point: with the panel shut, it is the only
                    // sign that the list is not the whole wardrobe.
                    Text(
                        when {
                            state.filtersExpanded -> "Hide filters"
                            state.query.activeFilterCount > 0 ->
                                "Filters (${state.query.activeFilterCount})"
                            else -> "Filters"
                        }
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                if (state.query.isNarrowed) {
                    TextButton(onClick = onFiltersCleared) { Text("Clear all") }
                }
            }

            if (state.filtersExpanded) {
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

            // Only once there is something to count. A count of zero is already
            // said, more usefully, by the empty state below.
            if (state.garments.isNotEmpty()) {
                Text(
                    if (state.garments.size == 1) "1 garment" else "${state.garments.size} garments",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            when {
                state.loading && state.garments.isEmpty() -> Centered {
                    CircularProgressIndicator()
                }

                // Reported, not swallowed. A read that failed must not look like
                // a wardrobe with nothing in it.
                state.error != null -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Couldn't read the wardrobe",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            state.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
                        )
                        TextButton(onClick = onRetry) { Text("Try again") }
                    }
                }

                // Three different things, because they call for three different
                // next moves: wait, widen, or add something.
                state.isFilteredEmpty -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (state.query.searchTerm != null) {
                                "Nothing matches \"${state.query.searchTerm}\"."
                            } else {
                                "Nothing matches these filters."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        TextButton(onClick = onFiltersCleared) { Text("Clear all") }
                    }
                }

                state.isEmpty -> Centered {
                    Text("No garments yet.", style = MaterialTheme.typography.bodyLarge)
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.garments, key = { it.id }) { garment ->
                        GarmentRow(garment) { onGarmentOpened(garment.id) }
                    }
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
                label = { Text("Brand") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = query.size,
                onValueChange = onSizeChanged,
                label = { Text("Size") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        FilterSection("Category") {
            for (category in GARMENT_CATEGORIES) {
                FilterPill(category.label, query.category == category.id) {
                    onCategoryTapped(category.id)
                }
            }
        }

        GARMENT_CATEGORIES.firstOrNull { it.id == query.category }?.let { category ->
            FilterSection("Type") {
                for (subcategory in category.subcategories) {
                    FilterPill(subcategory.sentence(), query.subcategory == subcategory) {
                        onSubcategoryTapped(subcategory)
                    }
                }
            }
        }

        FilterSection("Season") {
            for (season in Season.entries) {
                FilterPill(season.label(), query.season == season) { onSeasonTapped(season) }
            }
        }

        FilterSection("Occasion") {
            for (occasion in Occasion.entries) {
                FilterPill(occasion.label(), query.occasion == occasion) {
                    onOccasionTapped(occasion)
                }
            }
        }

        FilterSection("Colour") {
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
                "Include things I no longer wear",
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

private fun Season.label(): String =
    tag.replace('-', ' ').replaceFirstChar { it.uppercase() }

private fun Occasion.label(): String = id.replaceFirstChar { it.uppercase() }

private fun String.sentence(): String = replace('-', ' ').replaceFirstChar { it.uppercase() }

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun GarmentRow(garment: GarmentRecord, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
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
                    garment.subcategory ?: garment.category,
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
