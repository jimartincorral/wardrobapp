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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import com.wardrobapp.presentation.GarmentCaption
import com.wardrobapp.presentation.GarmentSort
import com.wardrobapp.presentation.WARDROBE_VIEW_CHOICES
import com.wardrobapp.presentation.WardrobeFacets
import com.wardrobapp.presentation.WardrobeLayout
import com.wardrobapp.presentation.WardrobeQuery
import com.wardrobapp.presentation.WardrobeView
import com.wardrobapp.presentation.captionField
import com.wardrobapp.presentation.paletteColorFor
import kotlinx.coroutines.launch

/** The scrolling body of the wardrobe, for tests that need to reach past the fold. */
const val WARDROBE_LIST = "wardrobe-list"

/**
 * One filter chip, by the value it carries.
 *
 * Needed because a brand appears twice on this screen when the sheet is open --
 * once as a chip and once on the garment wearing it -- so a matcher looking for
 * the text finds both and is asking about neither.
 */
/** The overflow that leads to bulk add. */
const val WARDROBE_ADD_MENU = "wardrobe-add-menu"

fun filterChipTag(value: String) = "filter-chip-$value"

/**
 * One colour swatch in the filter sheet, for a test that needs to tap one.
 *
 * Keyed by the hex, because that is what the sheet now draws: the swatches are the
 * colours the wardrobe holds, and a colour it holds may have no palette name.
 */
fun colorSwatchTag(hex: String) = "color-swatch-$hex"

/** The button that opens the list-or-grid menu. */
const val WARDROBE_VIEW_MENU = "wardrobe-view-menu"

/** The button that raises the filter sheet. */
const val WARDROBE_FILTER_ACTION = "wardrobe-filter-action"

/** The scrolling part of the filter sheet, for a test that needs to reach its last row. */
const val WARDROBE_FILTER_SHEET = "wardrobe-filter-sheet"

/**
 * One chip in the applied-filters row, by what it reads.
 *
 * Its own namespace rather than sharing [filterChipTag], and that is a bug rather
 * than tidiness: a chosen brand is drawn twice on this screen -- once in the sheet
 * as the choice and once in this row as what it did -- so one tag would answer to
 * two nodes exactly when a test cares which of them it has.
 *
 * Keyed by the label rather than the stored value because that is all this row
 * has: a season is already a translated word by the time it becomes a chip here.
 */
fun appliedFilterTag(label: String) = "applied-filter-$label"

/** One row of the grid-size menu, by the number of cells it asks for. A list is zero. */
fun wardrobeSizeTag(cellsAcross: Int) = "wardrobe-size-$cellsAcross"

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
    onCaptionSelected: (GarmentCaption) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wardrobe_title)) },
                // Four glyphs where there were two buttons and two words. The
                // count that used to be spelled out in "Filters (2)" is a badge on
                // the first of them, which is the only part of that label that was
                // saying anything a reader could not already see.
                actions = {
                    FilterAction(count = state.query.activeFilterCount, onTap = onFiltersToggled)
                    SortAction(sort = state.query.sort, onTap = onSortToggled)
                    ViewMenu(
                        current = state.view,
                        caption = state.caption,
                        onSelected = onViewSelected,
                        onCaptionSelected = onCaptionSelected,
                    )
                    AddMenu(onBulkAddRequested)
                },
            )
        },
        floatingActionButton = {
            val press = remember { MutableInteractionSource() }

            FloatingActionButton(
                onClick = onAddRequested,
                shape = RoundedCornerShape(16.dp),
                interactionSource = press,
                modifier = Modifier.size(56.dp).pressScale(press),
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.home_add_garment))
            }
        },
    ) { insets ->
        // What is narrowing the list, kept on screen rather than shut inside the
        // sheet. With the sheet closed this row is the only sign that the list is
        // not the whole wardrobe, and each chip undoes itself.
        //
        // Built out here rather than inside the grid: a lazy container's content
        // block is not a composition, so a label cannot be read from resources in
        // it.
        val applied = appliedFilters(
            query = state.query,
            onCategoryTapped = onCategoryTapped,
            onSubcategoryTapped = onSubcategoryTapped,
            onSeasonTapped = onSeasonTapped,
            onOccasionTapped = onOccasionTapped,
            onColorTapped = onColorTapped,
            onBrandTapped = onBrandTapped,
            onSizeTapped = onSizeTapped,
            onRetiredToggled = onRetiredToggled,
            onSortToggled = onSortToggled,
        )

        // Headers and garments in one list. A grid of one column is a list, so both
        // layouts are the same container and switching between them is a number
        // rather than a second screen. The headers span whatever that number is;
        // only the garments are cells.
        //
        // The filter panel used to be one of those headers. It is a modal sheet
        // now, which is what fixed the shape of the bug that put it here: a panel
        // taller than the phone, competing with the list for the same scroll.
        LazyVerticalGrid(
            columns = GridCells.Fixed(state.view.cellsAcross),
            // Tagged so a test can scroll it. A lazy container has not composed what
            // is below the fold, so "out of reach" and "not there at all" read the
            // same in an assertion unless the test can scroll first.
            modifier = Modifier.testTag(WARDROBE_LIST).fillMaxSize().padding(insets),
            // Eight round the grid and eight on each cell: sixteen between two
            // photos, sixteen from a photo to the screen's edge, which is the
            // screen padding everything else on this page uses.
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 88.dp),
        ) {
            fullWidth {
                SearchField(
                    value = state.query.search,
                    onValueChange = onSearchChanged,
                )
            }

            if (applied.isNotEmpty()) {
                fullWidth { AppliedFilters(applied, onFiltersCleared) }
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
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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
                            caption = state.caption,
                            modifier = Modifier.padding(8.dp),
                        ) { onGarmentOpened(garment.id) }
                    } else {
                        GarmentRow(
                            garment,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        ) { onGarmentOpened(garment.id) }
                    }
                }
            }
        }
    }

    if (state.filtersExpanded) {
        FilterSheet(
            query = state.query,
            facets = state.facets,
            shown = state.garments.size,
            onDismissed = onFiltersToggled,
            onCleared = onFiltersCleared,
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

/**
 * A header: one item across the whole width, whatever the grid is set to.
 *
 * Named rather than repeated, because every one of these would otherwise carry
 * the same span lambda and the point of them is that they are not cells.
 */
private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

/**
 * The way into the filters, with the count on it.
 *
 * The count was a word before -- "Filters (2)" -- and a badge says the same thing
 * in the space an icon takes. It is deliberately the same number the button has
 * always shown, which counts the search box and a non-default sort along with the
 * seven dimensions inside the sheet: the question it answers is "is this the whole
 * wardrobe", and the search box narrows the list exactly as much as a category does.
 */
@Composable
private fun FilterAction(count: Int, onTap: () -> Unit) {
    BadgedBox(
        badge = {
            if (count > 0) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Text("$count", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
    ) {
        IconButton(onClick = onTap, modifier = Modifier.testTag(WARDROBE_FILTER_ACTION)) {
            Icon(Glyph.Tune, contentDescription = stringResource(R.string.filters_show))
        }
    }
}

/**
 * Newest first, or oldest first.
 *
 * The glyph does not say which, so the description does -- and when the order is
 * not the default the applied-filters row carries it as a chip, where a reader
 * looking at the list rather than at the bar will find it.
 */
@Composable
private fun SortAction(sort: GarmentSort, onTap: () -> Unit) {
    val current = stringResource(
        if (sort == GarmentSort.NEWEST) R.string.sort_newest else R.string.sort_oldest
    )

    IconButton(onClick = onTap) {
        Icon(
            Glyph.SwapVert,
            contentDescription = stringResource(R.string.wardrobe_sort_current, current),
        )
    }
}

/**
 * The search box.
 *
 * Hand-built rather than an `OutlinedTextField`, which is a 56dp box with a
 * floating label above the text. The design asks for a 48dp filled pill with the
 * magnifier inside it and a cross that appears once there is something to clear --
 * a shape Material's own fields cannot be talked into without fighting their
 * label slot.
 */
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    // Read out here: a `semantics` block is not a composition, so a resource
    // fetched inside it would not compile.
    val label = stringResource(R.string.wardrobe_search)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = CircleShape,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.height(48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )

            Box(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                if (value.isEmpty()) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        // The placeholder above is drawn rather than announced, so
                        // the field itself has to carry the name of what it is.
                        .semantics { contentDescription = label },
                )
            }

            // Only once there is something to clear. A cross on an empty box is a
            // control that does nothing, which is worse than no control.
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.wardrobe_search_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** One thing narrowing the list: what it reads, and what undoes it. */
private data class AppliedFilter(val label: String, val onRemove: () -> Unit)

/**
 * Everything currently narrowing the wardrobe, in the order the sheet offers them.
 *
 * Built here rather than in :presentation because every entry is a *label*, and a
 * label is a resource: the query holds `tops` and a season enum, and what a reader
 * needs to see is "Tops" and "Summer" in their own language.
 *
 * The search box is not among them -- it is on screen two rows up, with its own
 * cross -- but the sort is, because otherwise a wardrobe running oldest-first says
 * so nowhere except in the description of an icon.
 */
@Composable
private fun appliedFilters(
    query: WardrobeQuery,
    onCategoryTapped: (String) -> Unit,
    onSubcategoryTapped: (String) -> Unit,
    onSeasonTapped: (Season) -> Unit,
    onOccasionTapped: (Occasion) -> Unit,
    onColorTapped: (String) -> Unit,
    onBrandTapped: (String) -> Unit,
    onSizeTapped: (String) -> Unit,
    onRetiredToggled: () -> Unit,
    onSortToggled: () -> Unit,
): List<AppliedFilter> = buildList {
    query.category?.let { add(AppliedFilter(categoryLabel(it)) { onCategoryTapped(it) }) }
    query.subcategory?.let { add(AppliedFilter(garmentTypeLabel(it)) { onSubcategoryTapped(it) }) }
    query.season?.let { add(AppliedFilter(stringResource(it.labelRes)) { onSeasonTapped(it) }) }
    query.occasion?.let { add(AppliedFilter(stringResource(it.labelRes)) { onOccasionTapped(it) }) }
    query.color?.let { add(AppliedFilter(colorLabel(it)) { onColorTapped(it) }) }
    query.brand.trim().takeIf { it.isNotEmpty() }?.let { add(AppliedFilter(it) { onBrandTapped(it) }) }
    query.size.trim().takeIf { it.isNotEmpty() }?.let { add(AppliedFilter(it) { onSizeTapped(it) }) }
    if (query.includeRetired) {
        add(AppliedFilter(stringResource(R.string.filter_include_retired), onRetiredToggled))
    }
    if (query.sort != GarmentSort.NEWEST) {
        add(AppliedFilter(stringResource(R.string.sort_oldest), onSortToggled))
    }
}

/**
 * The applied filters, on one line you scroll sideways.
 *
 * One line rather than a wrapping row: this sits between the search box and the
 * count, and a row that grows to three lines pushes the garments off the screen
 * for as long as the filters are on. The row must not be allowed to shrink to fit
 * either -- it is a scroll container inside a scrolling parent, and the trap in
 * Compose is the mirror of the one in CSS: a weight on the wrong child here does
 * what `flex: 1` does there.
 */
@Composable
private fun AppliedFilters(filters: List<AppliedFilter>, onCleared: () -> Unit) {
    val remove = stringResource(R.string.filter_remove)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (filter in filters) {
            InputChip(
                selected = true,
                onClick = filter.onRemove,
                label = { Text(filter.label) },
                trailingIcon = {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = remove,
                        modifier = Modifier.size(18.dp),
                    )
                },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag(appliedFilterTag(filter.label)),
            )
        }

        // At the end of the row rather than above it: with the chips scrolled to
        // the right it is where a thumb already is, and with two chips it is on
        // screen anyway.
        if (filters.size > 1) {
            TextButton(onClick = onCleared) { Text(stringResource(R.string.action_clear_filters)) }
        }
    }
}

/**
 * List or grid, and how wide a grid is.
 *
 * A menu rather than a row of buttons: four choices in a top bar that already
 * holds a title and three other actions would leave nothing legible, and this is a
 * decision made once in a while rather than flipped constantly.
 *
 * The button shows the density in force -- four boxes, nine, sixteen, or a list --
 * so it reports the current state rather than just offering to change it.
 *
 * A `Popup` rather than a `DropdownMenu` because the design specifies the surface:
 * 206dp wide, twelve-dp corners, and grown from its own top-right so it appears to
 * come out of the button rather than out of the middle of nowhere. Material's menu
 * is four-dp corners at whatever width its widest row asks for.
 */
@Composable
private fun ViewMenu(
    current: WardrobeView,
    caption: GarmentCaption,
    onSelected: (WardrobeView) -> Unit,
    onCaptionSelected: (GarmentCaption) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.wardrobe_view_options)
    val density = LocalDensity.current

    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(WARDROBE_VIEW_MENU)) {
            ViewGlyph(current, contentDescription = label)
        }

        if (open) {
            // Zero, then one a frame later. `animateFloatAsState` is handed its
            // target on the frame it is created, so a menu asked to animate
            // straight to 1f has nothing to animate from and simply appears --
            // the same trap the statistics bars are commented for.
            var opened by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) { opened = true }

            val grown by animateFloatAsState(
                targetValue = if (opened) 1f else 0f,
                animationSpec = springGentle(),
                label = "size-menu",
            )

            Popup(
                alignment = Alignment.TopEnd,
                // Under the button and ending where it ends, which is where the
                // design's "8dp from the right" already lands: the bar gives its
                // last action four dp of end padding and the button's own ripple
                // inset accounts for the rest. A positive x here would push the
                // menu *off* the right edge, since the alignment has already put
                // its end against the button's.
                offset = with(density) { IntOffset(0, 48.dp.roundToPx()) },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 3.dp,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .width(206.dp)
                        .growFrom(TransformOrigin(1f, 0f), grown),
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        MenuOverline(stringResource(R.string.wardrobe_size_menu))

                        for (choice in WARDROBE_VIEW_CHOICES) {
                            SizeRow(
                                choice = choice,
                                selected = choice.isCurrent(current),
                                onTap = {
                                    open = false
                                    onSelected(choice)
                                },
                            )
                        }

                        // Only while the grid is in force. A row already shows the
                        // type as its title and the brand under it, so there is
                        // nothing there to pick between, and an entry that changes
                        // nothing you can see is worse than an entry that is not
                        // there. The layout is chosen from this same menu, so the
                        // way to it is one tap away.
                        if (current.layout == WardrobeLayout.GRID) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                            MenuOverline(stringResource(R.string.wardrobe_view_section_show))

                            for (choice in GarmentCaption.entries) {
                                CaptionRow(
                                    label = stringResource(choice.labelRes),
                                    selected = choice == caption,
                                    onTap = {
                                        open = false
                                        onCaptionSelected(choice)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The glyph for a density: two boxes, four, nine, or the list.
 *
 * A composable that draws rather than a function that returns, because one of the
 * four comes from Material's core set as an `ImageVector` and the other three are
 * vendored drawables loaded as a `Painter`. `Icon` has an overload for each; a
 * single return type would mean converting one of them for no reason.
 */
@Composable
private fun ViewGlyph(
    view: WardrobeView,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    if (view.layout == WardrobeLayout.LIST) {
        Icon(Icons.AutoMirrored.Filled.List, contentDescription, modifier)
        return
    }

    val glyph = when {
        view.columns <= 2 -> Glyph.ViewModule
        view.columns == 3 -> Glyph.GridView
        else -> Glyph.Apps
    }

    Icon(glyph, contentDescription, modifier)
}

/**
 * A heading inside the menu.
 *
 * Needed once there were two things being chosen in it: "List / Grid of 3 / Brand
 * / Category" in one column reads as one set of alternatives, and picking a
 * caption would look like it should have replaced the layout.
 *
 * Uppercased here rather than in the string table, so that the resource stays a
 * word a translator can read and this stays a decision about type.
 */
@Composable
private fun MenuOverline(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 11.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.55.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** One density: its glyph, its name, how many go across, and a tick when it is in force. */
@Composable
private fun SizeRow(choice: WardrobeView, selected: Boolean, onTap: () -> Unit) {
    MenuRow(
        selected = selected,
        onTap = onTap,
        modifier = Modifier.testTag(wardrobeSizeTag(if (choice.layout == WardrobeLayout.LIST) 0 else choice.columns)),
        leading = { ViewGlyph(choice, contentDescription = null, modifier = Modifier.size(20.dp)) },
        label = when (choice.layout) {
            WardrobeLayout.LIST -> stringResource(R.string.wardrobe_view_list)
            WardrobeLayout.GRID -> stringResource(
                R.string.wardrobe_size_per_row,
                stringResource(choice.sizeNameRes()),
                choice.columns,
            )
        },
    )
}

/** Large, medium, small -- the three widths a phone has room for. */
private fun WardrobeView.sizeNameRes(): Int = when {
    columns <= 2 -> R.string.wardrobe_size_large
    columns == 3 -> R.string.wardrobe_size_medium
    else -> R.string.wardrobe_size_small
}

/** What a cell says under its photo. No glyph: there is no icon for "brand". */
@Composable
private fun CaptionRow(label: String, selected: Boolean, onTap: () -> Unit) {
    MenuRow(selected = selected, onTap = onTap, leading = null, label = label)
}

/**
 * One row of the grid-size menu.
 *
 * The row in force is filled rather than merely ticked, and still ticked as well:
 * a fill alone is a colour difference, and this is exactly the kind of state that
 * has to survive somebody who cannot see the difference between two purples.
 */
@Composable
private fun MenuRow(
    selected: Boolean,
    onTap: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(background)
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(modifier = Modifier.size(20.dp)) { leading() }
            Spacer(modifier = Modifier.width(12.dp))
        }

        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(20.dp),
            )
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

/**
 * One garment as a cell.
 *
 * The photo at the 3:4 every garment photo in this app is, and one line under it.
 * What that line says is [caption]'s to decide -- the brand was the only answer
 * for a while, on the reasoning that it is the one thing a photo does not show,
 * which is true of some wardrobes and not of others.
 *
 * Which field a given garment can actually answer with is `captionField`'s, in
 * :presentation, where a test can hold it: the failure worth guarding against is a
 * cell whose line comes out blank, and that happens only on the garments missing
 * the field asked for.
 */
@Composable
private fun GarmentCell(
    garment: GarmentRecord,
    caption: GarmentCaption,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .pressScale(press, pressedScale = 0.96f)
            .clickable(interactionSource = press, indication = null, onClick = onClick),
    ) {
        AsyncImage(
            model = garment.displayImage,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(8.dp))
                .background(photoSurface()),
        )

        Text(
            when (garment.captionField(caption)) {
                // As typed by whoever entered it, where the other two go through
                // the vocabulary: a brand is not a word this app knows.
                GarmentCaption.BRAND -> garment.brand.orEmpty()
                GarmentCaption.TYPE -> garmentTypeLabel(garment.subcategory.orEmpty())
                GarmentCaption.CATEGORY -> categoryLabel(garment.category)
            },
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * The filter sheet.
 *
 * Every dimension here was already ported and tested with nothing able to reach
 * it: `GarmentFilter` had six fields that no code ever set, and
 * `GarmentQueries.Filters` a seventh and an eighth. What is new is the way in.
 *
 * A modal sheet rather than a panel inside the list, which is what it was. The
 * panel was taller than a phone, and as one item of the same scroll it competed
 * with the garments for the screen -- the bug that shipped twice. A sheet has its
 * own scroll and its own height, so neither can crowd the other out, and it closes
 * three ways: the scrim, the cross, and the button that says how many garments
 * are waiting behind it.
 *
 * Subcategories appear only once a category is chosen, because they are its
 * subcategories -- offering all of them at once would let you filter by a type
 * the chosen category does not have and show nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    query: WardrobeQuery,
    facets: WardrobeFacets,
    shown: Int,
    onDismissed: () -> Unit,
    onCleared: () -> Unit,
    onBrandTapped: (String) -> Unit,
    onSizeTapped: (String) -> Unit,
    onCategoryTapped: (String) -> Unit,
    onSubcategoryTapped: (String) -> Unit,
    onSeasonTapped: (Season) -> Unit,
    onOccasionTapped: (Occasion) -> Unit,
    onColorTapped: (String) -> Unit,
    onRetiredToggled: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Eighty-two percent of the screen, so the list behind it stays visible as the
    // thing being narrowed rather than being replaced by a second screen.
    val tallest = (LocalConfiguration.current.screenHeightDp * 0.82f).dp

    // The scrim and the drag already animate: Material runs its own hide and then
    // calls back. The two controls *on* the sheet did not -- calling back directly
    // takes the sheet out of the composition on that frame, so it disappeared
    // instead of leaving. This slides it out first and reports the dismissal when
    // it has gone.
    val close = {
        scope.launch { sheet.hide() }.invokeOnCompletion { onDismissed() }
        Unit
    }

    ModalBottomSheet(
        onDismissRequest = onDismissed,
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.filters_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )

            if (query.isNarrowed) {
                TextButton(onClick = onCleared) {
                    Icon(
                        Glyph.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(R.string.action_clear_filters),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

            IconButton(onClick = close) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
            }
        }

        Column(
            modifier = Modifier
                .testTag(WARDROBE_FILTER_SHEET)
                .heightIn(max = tallest)
                // `fill = false` so a sheet with two rows in it is two rows tall
                // rather than eighty-two percent of a screen with a gap under it.
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            // A row is left out entirely when the wardrobe has nothing to put in it
            // -- a heading over an empty line is worse than no heading.
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
                            shape = RoundedCornerShape(8.dp),
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
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
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

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                pluralStringResource(
                    R.plurals.filters_applied,
                    query.activeFilterCount,
                    query.activeFilterCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )

            val press = remember { MutableInteractionSource() }

            Button(
                onClick = close,
                interactionSource = press,
                modifier = Modifier.height(CTA_HEIGHT).pressScale(press),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    // The number the sheet is standing in front of. Both the
                    // reason to close it and the answer to what the filters did.
                    pluralStringResource(R.plurals.filters_show_results, shown, shown),
                    style = ctaLabel(),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        // Clear of the gesture area.
        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * One row of choices: a heading, then a single line you scroll sideways.
 *
 * A wrapping row was the obvious thing and the wrong one. Six categories and a
 * dozen colours wrapped to three or four lines each, so the sheet was taller than
 * any phone and the rows below it were somewhere off the bottom of a scroll. One
 * line per dimension keeps every heading visible at once, and a row that is too
 * long for the screen says so by being scrollable rather than by growing.
 */
@Composable
private fun FilterSection(title: String, content: @Composable RowScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
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
        shape = RoundedCornerShape(8.dp),
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
    val press = remember { MutableInteractionSource() }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .pressNudge(press)
            .clickable(interactionSource = press, indication = null, onClick = onClick),
    ) {
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
                    .background(photoSurface()),
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
