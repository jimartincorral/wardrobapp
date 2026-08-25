package com.wardrobapp.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import com.wardrobapp.presentation.GarmentSort
import com.wardrobapp.presentation.paletteColorFor
import com.wardrobapp.presentation.WardrobeFacets
import com.wardrobapp.presentation.WARDROBE_VIEW_CHOICES
import com.wardrobapp.presentation.WardrobeLayout
import com.wardrobapp.presentation.WardrobeQuery
import com.wardrobapp.presentation.WardrobeView

/** The scrolling body of the wardrobe, for tests that need to reach past the fold. */
const val WARDROBE_LIST = "wardrobe-list"

/**
 * One filter chip, by the value it carries.
 *
 * Needed because a brand appears twice on this screen when the panel is open --
 * once as a chip and once on the garment wearing it -- so a matcher looking for
 * the text finds both and is asking about neither.
 */
/** The overflow that leads to bulk add. */
const val WARDROBE_ADD_MENU = "wardrobe-add-menu"

fun filterChipTag(value: String) = "filter-chip-$value"

/**
 * One colour swatch in the filter panel, for a test that needs to tap one.
 *
 * Keyed by the hex, because that is what the panel now draws: the swatches are the
 * colours the wardrobe holds, and a colour it holds may have no palette name.
 */
fun colorSwatchTag(hex: String) = "color-swatch-$hex"

/** The button that opens the list-or-grid menu. */
const val WARDROBE_VIEW_MENU = "wardrobe-view-menu"

/**
 * The wardrobe list.
 *
 * Layout only. What the list contains, in what order, and what its colours mean
 * were all decided before anything reached here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WardrobeScreen(
    state: WardrobeViewModel.State,
    onSearchChanged: (String) -> Unit,
    onSortToggled: () -> Unit,
    onRetry: () -> Unit,
    onGarmentOpened: (String) -> Unit,
    onAddRequested: () -> Unit,
    onBulkAddRequested: () -> Unit,
    onSettingsRequested: () -> Unit,
    onFiltersToggled: () -> Unit,
    onFiltersCleared: () -> Unit,
    onBrandTapped: (String) -> Unit,
    onSizeTapped: (String) -> Unit,
    onCategoryTapped: (String) -> Unit,
    onSubcategoryTapped: (String) -> Unit,
    onSeasonTapped: (Season) -> Unit,
    onOccasionTapped: (Occasion) -> Unit,
    onColorTapped: (String) -> Unit,
    onRetiredToggled: () -> Unit,
    onViewSelected: (WardrobeView) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wardrobe_title)) },
                actions = {
                    ViewMenu(current = state.view, onSelected = onViewSelected)
                    AddMenu(onBulkAddRequested)
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
        // A grid of one column is a list, so both layouts are the same container and
        // switching between them is a number rather than a second screen. The
        // headers span whatever that number is; only the garments are cells.
        LazyVerticalGrid(
            columns = GridCells.Fixed(state.view.cellsAcross),
            // Tagged so a test can scroll it. A lazy container has not composed what
            // is below the fold, so "out of reach" and "not there at all" read the
            // same in an assertion unless the test can scroll first.
            modifier = Modifier.testTag(WARDROBE_LIST).fillMaxSize().padding(insets),
            // Room at the bottom so the add button does not sit on the last card.
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            fullWidth {
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

            fullWidth {
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
                fullWidth {
                    FilterPanel(
                        query = state.query,
                        facets = state.facets,
                        onBrandTapped = onBrandTapped,
                        onSizeTapped = onSizeTapped,
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
                fullWidth {
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
                state.loading && state.garments.isEmpty() -> fullWidth {
                    Message { CircularProgressIndicator() }
                }

                // Reported, not swallowed. A read that failed must not look like
                // a wardrobe with nothing in it.
                state.error != null -> fullWidth {
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
                state.isFilteredEmpty -> fullWidth {
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

                state.isEmpty -> fullWidth {
                    Message {
                        Text(stringResource(R.string.wardrobe_empty), style = MaterialTheme.typography.bodyLarge)
                    }
                }

                else -> items(state.garments, key = { it.id }) { garment ->
                    if (state.view.layout == WardrobeLayout.GRID) {
                        GarmentCell(
                            garment,
                            modifier = Modifier.padding(8.dp),
                        ) { onGarmentOpened(garment.id) }
                    } else {
                        GarmentRow(
                            garment,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        ) { onGarmentOpened(garment.id) }
                    }
                }
            }
        }
    }
}

/**
 * A header: one item across the whole width, whatever the grid is set to.
 *
 * Named rather than repeated, because every one of these would otherwise carry
 * the same span lambda and the point of them is that they are not cells.
 */
private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

/**
 * List or grid, and how wide a grid is.
 *
 * A menu rather than a row of buttons: four choices in a top bar that already
 * holds a title and settings would leave nothing legible, and this is a decision
 * made once in a while rather than flipped constantly.
 *
 * The button shows the layout in force. There is no grid in the icon set this app
 * carries -- `material-icons-core`, chosen over the extended set that would add
 * several megabytes for one glyph -- so the grid one is four boxes, drawn here.
 */
@Composable
private fun ViewMenu(current: WardrobeView, onSelected: (WardrobeView) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.wardrobe_view_options)

    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(WARDROBE_VIEW_MENU)) {
            if (current.layout == WardrobeLayout.GRID) {
                GridGlyph(modifier = Modifier.semantics { contentDescription = label })
            } else {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = label)
            }
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (choice in WARDROBE_VIEW_CHOICES) {
                DropdownMenuItem(
                    text = { Text(viewChoiceLabel(choice)) },
                    onClick = {
                        open = false
                        onSelected(choice)
                    },
                    // A tick on the one in force, since the button's own icon says
                    // list or grid but not how wide.
                    trailingIcon = {
                        if (choice.isCurrent(current)) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                )
            }
        }
    }
}

/**
 * The way to the bulk-add queue.
 *
 * A menu rather than a second button: the add button is for adding a garment, and
 * cataloguing a drawerful is a different job that would be a poor default. A menu
 * of one looks thin, and is still better than two buttons a thumb has to tell
 * apart.
 */
@Composable
private fun AddMenu(onBulkAddRequested: () -> Unit) {
    var open by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(WARDROBE_ADD_MENU)) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.wardrobe_add_options),
            )
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.bulk_add_menu)) },
                onClick = {
                    open = false
                    onBulkAddRequested()
                },
            )
        }
    }
}

/** "List", or "Grid of 3" -- the count is the thing being chosen. */
@Composable
private fun viewChoiceLabel(choice: WardrobeView): String = when (choice.layout) {
    WardrobeLayout.LIST -> stringResource(R.string.wardrobe_view_list)
    WardrobeLayout.GRID -> stringResource(R.string.wardrobe_view_grid, choice.columns)
}

/** Four boxes: the grid icon this app has no icon for. */
@Composable
private fun GridGlyph(modifier: Modifier = Modifier) {
    val tint = LocalContentColor.current

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(2) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(tint),
                    )
                }
            }
        }
    }
}

/**
 * One garment as a cell.
 *
 * The photo at the 3:4 every garment photo in this app is, and the brand under it
 * -- which is how a wall of photos is read, and the one thing a photo does not
 * show. A garment with no brand gets its type instead rather than a blank line.
 */
@Composable
private fun GarmentCell(garment: GarmentRecord, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier = modifier.clickable(onClick = onClick)) {
        AsyncImage(
            model = garment.displayImage,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )

        Text(
            garment.brand?.takeIf { it.isNotBlank() }
                ?: garment.subcategory?.let { garmentTypeLabel(it) }
                ?: categoryLabel(garment.category),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
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
@Composable
private fun FilterPanel(
    query: WardrobeQuery,
    facets: WardrobeFacets,
    onBrandTapped: (String) -> Unit,
    onSizeTapped: (String) -> Unit,
    onCategoryTapped: (String) -> Unit,
    onSubcategoryTapped: (String) -> Unit,
    onSeasonTapped: (Season) -> Unit,
    onOccasionTapped: (Occasion) -> Unit,
    onColorTapped: (String) -> Unit,
    onRetiredToggled: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        // A row is left out entirely when the wardrobe has nothing to put in it --
        // a heading over an empty line is worse than no heading.
        if (facets.categories.isNotEmpty()) {
            FilterSection(stringResource(R.string.filter_section_category)) {
                for (category in facets.categories) {
                    FilterPill(categoryLabel(category), category, query.category == category) {
                        onCategoryTapped(category)
                    }
                }
            }
        }

        if (facets.subcategories.isNotEmpty()) {
            FilterSection(stringResource(R.string.filter_section_type)) {
                for (subcategory in facets.subcategories) {
                    FilterPill(
                        garmentTypeLabel(subcategory),
                        subcategory,
                        query.subcategory == subcategory,
                    ) {
                        onSubcategoryTapped(subcategory)
                    }
                }
            }
        }

        if (facets.seasons.isNotEmpty()) {
            FilterSection(stringResource(R.string.filter_section_season)) {
                for (season in facets.seasons) {
                    FilterPill(stringResource(season.labelRes), season.name, query.season == season) {
                        onSeasonTapped(season)
                    }
                }
            }
        }

        if (facets.occasions.isNotEmpty()) {
            FilterSection(stringResource(R.string.filter_section_occasion)) {
                for (occasion in facets.occasions) {
                    FilterPill(
                        stringResource(occasion.labelRes),
                        occasion.name,
                        query.occasion == occasion,
                    ) {
                        onOccasionTapped(occasion)
                    }
                }
            }
        }

        if (facets.colors.isNotEmpty()) {
            FilterSection(stringResource(R.string.filter_section_colour)) {
                for (hex in facets.colors) {
                    // A colour that will not parse is the multi-colour sentinel
                    // rather than a colour, and is left out here as it is on the
                    // form: drawn as a plain circle it would be a second grey
                    // swatch that meant something else.
                    val swatch = hex.toComposeColor() ?: continue
                    val selected = query.color.equals(hex, ignoreCase = true)

                    // A chip like every other filter, not a bare circle: brand,
                    // size, season and the rest all carry their name as text, and
                    // a colour told apart only by a small disc of it is unreadable
                    // for anyone who cannot tell the shades apart by eye alone.
                    FilterChip(
                        selected = selected,
                        onClick = { onColorTapped(hex) },
                        label = { Text(colorLabel(hex)) },
                        leadingIcon = { ColorSwatch(swatch) },
                        modifier = Modifier.testTag(colorSwatchTag(hex)),
                    )
                }
            }
        }

        // Brands and sizes, from the wardrobe rather than from a text box. They
        // used to be two boxes you typed into from memory, spelled right, which is
        // the worst way to ask for a value the app already knows.
        if (facets.brands.isNotEmpty()) {
            FilterSection(stringResource(R.string.filter_brand)) {
                for (brand in facets.brands) {
                    // As typed by whoever entered it: a brand is not a word this
                    // app gets to capitalize.
                    FilterPill(brand, brand, query.brand.equals(brand, ignoreCase = true)) {
                        onBrandTapped(brand)
                    }
                }
            }
        }

        if (facets.sizes.isNotEmpty()) {
            FilterSection(stringResource(R.string.filter_size)) {
                for (size in facets.sizes) {
                    FilterPill(size, size, query.size.equals(size, ignoreCase = true)) {
                        onSizeTapped(size)
                    }
                }
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

/**
 * One row of choices: a heading, then a single line you scroll sideways.
 *
 * A wrapping row was the obvious thing and the wrong one. Six categories and a
 * dozen colours wrapped to three or four lines each, so the panel was taller than
 * any phone and the rows below it were somewhere off the bottom of a scroll. One
 * line per dimension keeps every heading visible at once, and a row that is too
 * long for the screen says so by being scrollable rather than by growing.
 */
@Composable
private fun FilterSection(title: String, content: @Composable RowScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    Row(
        // Its own scroll state per row, remembered on the heading, so scrolling the
        // colours does not drag the brands along with them.
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * One choice.
 *
 * [value] is what the chip stands for rather than what it reads: a category chip
 * says "Tops" and carries `tops`, and the tag follows the value so a test names
 * the same thing the callback will hand back.
 */
@Composable
private fun FilterPill(label: String, value: String, selected: Boolean, onTap: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onTap,
        label = { Text(label) },
        modifier = Modifier.testTag(filterChipTag(value)),
    )
}

// The three label helpers that used to live here are gone: Vocabulary.kt answers
// for seasons, occasions, categories and types now, out of resources.

/**
 * A stored colour's name, or the hex itself when it is not one of the palette's.
 *
 * The same rule the statistics page's colour bars use, in StatisticsScreen.kt:
 * the reader never sees a hex in a wardrobe with nothing hand-entered in it, and
 * a hand-entered one is shown as stored rather than hidden behind a name that is
 * not true of it.
 */
@Composable
private fun colorLabel(hex: String): String = paletteColorFor(hex)?.first?.let { paletteLabel(it) } ?: hex


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
