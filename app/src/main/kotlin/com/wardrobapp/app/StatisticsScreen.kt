package com.wardrobapp.app

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wardrobapp.data.DuplicateGarmentGroup
import com.wardrobapp.data.GapOutfit
import com.wardrobapp.data.GapWithPhotos
import com.wardrobapp.domain.GapEvidence
import com.wardrobapp.domain.MIN_WARDROBE_FOR_GAPS
import com.wardrobapp.domain.OutfitSlot
import com.wardrobapp.domain.PhantomGarment
import com.wardrobapp.presentation.BrandSort
import com.wardrobapp.presentation.ColorBar
import com.wardrobapp.presentation.LifespanBar
import com.wardrobapp.presentation.MULTI_SWATCH
import com.wardrobapp.presentation.NO_SUBCATEGORY
import com.wardrobapp.presentation.StatBar
import com.wardrobapp.presentation.StatisticsView
import com.wardrobapp.presentation.WardrobeLink
import com.wardrobapp.presentation.paletteColorFor
import kotlinx.coroutines.delay

/**
 * The "show these in the wardrobe" button on one row.
 *
 * Keyed on the value the row stands for rather than on its position, so a test taps
 * the row it means and not the third one down.
 */
fun statFilterTag(value: String) = "statistics-filter-$value"

/** One photo in a duplicate group. Distinct from [statFilterTag]: both are
 * garment ids, and two sections claiming one tag would leave a test tapping
 * whichever the tree happened to reach first. */
fun duplicateTag(garmentId: String) = "statistics-duplicate-$garmentId"

/** The parts of the page that open and shut, all shut to begin with. */
enum class StatisticsSection { CATEGORY, COLOUR, BRAND, LIFESPAN, GAPS, DUPLICATES }

/** One gap's card, keyed on the slot it is about rather than on its position. */
fun gapTag(slot: OutfitSlot) = "statistics-gap-$slot"

/**
 * The scrolling page, for tests that need to reach past the fold.
 *
 * Six tiles and four headings is taller than the screen a Robolectric test gets,
 * so "below the fold" and "not there at all" read the same in an assertion unless
 * the test can scroll first -- the same reason the wardrobe's list is tagged.
 */
const val STATISTICS_PAGE = "statistics-page"

/**
 * What the wardrobe is made of, and how long the things you stop wearing lasted.
 *
 * One page where there were two. An "Analytics" tab held the counts of what is in
 * use and retired, a category chart and the lifespan bars, with a link to a
 * "Statistics" screen that held the same category counts again plus colours,
 * brands and subcategories. Two names for one question, and the same numbers
 * drawn twice.
 *
 * So: six tiles, then every breakdown as a section you open. Shut to begin with,
 * because six charts unrolled is a page nobody reads to the bottom of -- and the
 * tiles are the answer most visits are looking for.
 *
 * Layout and words only: every bar arrives with its length already decided by
 * :presentation. What is left here is what that module deliberately does not own
 * -- turning keys into text, and drawing a swatch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    state: StatisticsViewModel.State,
    onCategoryTapped: (String) -> Unit,
    onLinkRequested: (WardrobeLink?) -> Unit,
    onGarmentOpened: (String) -> Unit,
    onBrandSortChanged: (BrandSort) -> Unit,
    onSectionTapped: (StatisticsSection) -> Unit,
    onGapAddRequested: (PhantomGarment) -> Unit,
    onRetry: () -> Unit,
) {
    // No back arrow: this is a tab now, not a screen reached from one.
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.statistics_title)) }) }) { insets ->
        val view = state.view

        when {
            state.loading && view == null -> Box(
                modifier = Modifier.fillMaxSize().padding(insets),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            view == null -> Column(
                modifier = Modifier.fillMaxWidth().padding(insets).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.error_wardrobe_unreadable), style = MaterialTheme.typography.titleMedium)
                state.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }

            else -> Body(
                view = view,
                duplicates = state.duplicates,
                gaps = state.gaps,
                expanded = state.expanded,
                openSections = state.openSections,
                brandSort = state.brandSort,
                insets = insets,
                onCategoryTapped = onCategoryTapped,
                onLinkRequested = onLinkRequested,
                onGarmentOpened = onGarmentOpened,
                onBrandSortChanged = onBrandSortChanged,
                onSectionTapped = onSectionTapped,
                onGapAddRequested = onGapAddRequested,
            )
        }
    }
}

@Composable
private fun Body(
    view: StatisticsView,
    duplicates: List<DuplicateGarmentGroup>?,
    gaps: List<GapWithPhotos>?,
    expanded: Set<String>,
    openSections: Set<StatisticsSection>,
    brandSort: BrandSort,
    insets: PaddingValues,
    onCategoryTapped: (String) -> Unit,
    onLinkRequested: (WardrobeLink?) -> Unit,
    onGarmentOpened: (String) -> Unit,
    onBrandSortChanged: (BrandSort) -> Unit,
    onSectionTapped: (StatisticsSection) -> Unit,
    onGapAddRequested: (PhantomGarment) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.testTag(STATISTICS_PAGE).padding(insets),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(R.string.statistics_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Six numbers, two rows: what the wardrobe holds, then how varied it is.
        // Items is everything, in use and retired together -- the two tiles beside
        // it are the split, and a tile that repeated one of them would be a number
        // with nothing to say.
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // All three count garments, so all three lead to them. Retired
                // asks for retired garments, since the plain wardrobe shows none.
                Tile(
                    label = stringResource(R.string.statistics_items),
                    value = view.items,
                    onClick = { onLinkRequested(WardrobeLink.Retired) },
                    modifier = Modifier.weight(1f),
                )
                Tile(
                    label = stringResource(R.string.statistics_in_use),
                    value = view.inUse,
                    onClick = { onLinkRequested(null) },
                    modifier = Modifier.weight(1f),
                )
                Tile(
                    label = stringResource(R.string.statistics_retired),
                    value = view.retired,
                    onClick = { onLinkRequested(WardrobeLink.Retired) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // These three count labels rather than garments -- there is no
                // list of colours to open -- so they are numbers and nothing more.
                Tile(
                    label = stringResource(R.string.statistics_categories),
                    value = view.distinctCategories.toLong(),
                    modifier = Modifier.weight(1f),
                )
                Tile(
                    label = stringResource(R.string.statistics_colours),
                    value = view.distinctColors.toLong(),
                    modifier = Modifier.weight(1f),
                )
                Tile(
                    label = stringResource(R.string.statistics_brands),
                    value = view.distinctBrands.toLong(),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (view.isEmpty) {
            // Every section below would be empty, so on an empty wardrobe the tiles
            // and one explanation are the whole page.
            item {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.statistics_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.statistics_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            return@LazyColumn
        }

        // A breakdown with nothing in it is left out entirely rather than offered
        // as a section that opens onto nothing. Lifespans are the exception, below.
        if (view.categories.isNotEmpty()) {
            val open = StatisticsSection.CATEGORY in openSections

            item {
                SectionHeader(
                    title = stringResource(R.string.statistics_by_category),
                    open = open,
                    // Only once it is open: a hint about tapping rows is noise
                    // beside a section whose rows are not on screen.
                    hint = stringResource(R.string.statistics_expand_hint),
                    onClick = { onSectionTapped(StatisticsSection.CATEGORY) },
                )
            }

            if (open) {
                item {
                    Chart {
                        for ((index, bar) in view.categories.withIndex()) {
                            val isOpen = bar.key in expanded

                            BarRow(
                                label = categoryLabel(bar.key),
                                fraction = bar.fraction,
                                index = index,
                                value = "${bar.count}",
                                chevronTurn = if (isOpen) 180f else 0f,
                                onClick = { onCategoryTapped(bar.key) },
                                // What tapping does, for a screen reader, which the
                                // chevron only says visually.
                                clickLabel = stringResource(
                                    if (isOpen) R.string.statistics_collapse else R.string.statistics_expand
                                ),
                                // Beside the row rather than being the row: tapping
                                // the row opens the types underneath, which is worth
                                // keeping, so leaving the chart needs its own target.
                                action = {
                                    ShowInWardrobe(
                                        label = categoryLabel(bar.key),
                                        tag = statFilterTag(bar.key),
                                        onClick = { onLinkRequested(WardrobeLink.Category(bar.key)) },
                                    )
                                },
                            )

                            if (isOpen) {
                                Subcategories(
                                    category = bar.key,
                                    onLinkRequested = onLinkRequested,
                                    // Absent rather than empty for a category whose
                                    // garments all predate subcategories: the query
                                    // returns no group at all, and the dash below is
                                    // what that looks like.
                                    bars = view.subcategories[bar.key].orEmpty(),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (view.colors.isNotEmpty()) {
            val open = StatisticsSection.COLOUR in openSections

            item {
                SectionHeader(
                    title = stringResource(R.string.statistics_by_colour),
                    open = open,
                    onClick = { onSectionTapped(StatisticsSection.COLOUR) },
                )
            }

            if (open) {
                item {
                    Chart {
                        for ((index, bar) in view.colors.withIndex()) {
                            val label = bar.colorLabel()

                            BarRow(
                                label = label,
                                fraction = bar.fraction,
                                index = index,
                                value = "${bar.count}",
                                swatch = { Swatch(bar.swatch) },
                                action = {
                                    ShowInWardrobe(
                                        label = label,
                                        tag = statFilterTag(bar.key),
                                        // The value a garment stores, which is what
                                        // the filter compares -- not the palette's
                                        // name for it, and not the swatch, which is
                                        // a hex chosen for drawing.
                                        onClick = { onLinkRequested(WardrobeLink.Colour(bar.key)) },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        if (view.brands.isNotEmpty()) {
            val open = StatisticsSection.BRAND in openSections

            item {
                SectionHeader(
                    title = stringResource(R.string.statistics_by_brand),
                    open = open,
                    onClick = { onSectionTapped(StatisticsSection.BRAND) },
                )
            }

            if (open) {
                // Inside the section rather than beside its title: the title is a
                // button now, and a chip inside a button is two taps in one place.
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = brandSort == BrandSort.COUNT,
                            onClick = { onBrandSortChanged(BrandSort.COUNT) },
                            label = { Text(stringResource(R.string.statistics_sort_count)) },
                        )
                        FilterChip(
                            selected = brandSort == BrandSort.ALPHA,
                            onClick = { onBrandSortChanged(BrandSort.ALPHA) },
                            label = { Text(stringResource(R.string.statistics_sort_name)) },
                        )
                    }
                }

                item {
                    Chart {
                        for ((index, bar) in view.brands.withIndex()) {
                            // A brand is what the wearer typed, so it is shown as
                            // typed rather than capitalized -- and filtered by the
                            // same string.
                            BarRow(
                                label = bar.key,
                                fraction = bar.fraction,
                                index = index,
                                value = "${bar.count}",
                                action = {
                                    ShowInWardrobe(
                                        label = bar.key,
                                        tag = statFilterTag(bar.key),
                                        onClick = { onLinkRequested(WardrobeLink.Brand(bar.key)) },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        // Always offered, even with nothing retired: "nothing has been retired yet"
        // is the answer to the question, and a missing section reads as the app not
        // measuring lifespans at all -- which is what the old two-screen split made
        // people assume.
        run {
            val open = StatisticsSection.LIFESPAN in openSections

            item {
                SectionHeader(
                    title = stringResource(R.string.statistics_lifespan),
                    open = open,
                    onClick = { onSectionTapped(StatisticsSection.LIFESPAN) },
                )
            }

            if (open) {
                item {
                    Chart {
                        if (view.lifespans.isEmpty()) {
                            Text(
                                stringResource(R.string.statistics_no_lifespan),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                            )
                        } else {
                            for ((index, bar) in view.lifespans.withIndex()) {
                                val label = bar.label()

                                BarRow(
                                    label = label,
                                    fraction = bar.fraction,
                                    index = index,
                                    value = stringResource(R.string.statistics_days, bar.days),
                                    // Wider than a count: "365d" does not fit where
                                    // a two-digit tally does.
                                    valueWidth = 44.dp,
                                    // A lifespan bar is one particular garment, not
                                    // a group, so this opens that garment rather
                                    // than a list filtered to one thing.
                                    action = {
                                        OpenGarment(
                                            label = label,
                                            tag = statFilterTag(bar.entry.garmentId),
                                            onClick = { onGarmentOpened(bar.entry.garmentId) },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // The last two sections ask you to do something about what they found,
        // rather than telling you what is there. This one first, because adding a
        // garment you are missing is a happier errand than deciding which of two
        // near-identical shirts to get rid of.
        run {
            val open = StatisticsSection.GAPS in openSections

            item {
                SectionHeader(
                    title = stringResource(R.string.statistics_gaps),
                    open = open,
                    hint = stringResource(R.string.statistics_gaps_hint),
                    onClick = { onSectionTapped(StatisticsSection.GAPS) },
                )
            }

            if (open) {
                item {
                    Chart {
                        when {
                            // "Still looking", and it has to read as that: this is
                            // the slowest thing the app computes, and "nothing is
                            // missing" shown while it runs would be a claim made
                            // before anything had checked.
                            gaps == null -> Box(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }

                            // Two different silences, told apart. A wardrobe too
                            // small to reason about gets no advice by design, and
                            // saying "nothing is missing" to somebody with six
                            // garments would be the app declining to answer while
                            // sounding like it had.
                            gaps.isEmpty() && view.inUse < MIN_WARDROBE_FOR_GAPS -> Text(
                                stringResource(R.string.statistics_gaps_too_few),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                            )

                            gaps.isEmpty() -> Text(
                                stringResource(R.string.statistics_no_gaps),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                            )

                            else -> for (gap in gaps) {
                                GapRow(gap = gap, onAdd = onGapAddRequested)
                            }
                        }
                    }
                }
            }
        }

        run {
            val open = StatisticsSection.DUPLICATES in openSections

            item {
                SectionHeader(
                    title = stringResource(R.string.statistics_duplicates),
                    open = open,
                    hint = stringResource(R.string.statistics_duplicates_hint),
                    onClick = { onSectionTapped(StatisticsSection.DUPLICATES) },
                )
            }

            if (open) {
                item {
                    Chart {
                        when {
                            // Null is "still looking", and it has to read as that:
                            // the sweep is the one thing on this page that takes
                            // long enough to notice, and showing "nothing looks
                            // like anything else" while it runs would tell somebody
                            // their wardrobe is clean before anything had checked.
                            duplicates == null -> Box(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }

                            // Offered even when it finds nothing, for the reason
                            // the lifespan section is: "nothing looks like anything
                            // else" is the answer to the question, where a missing
                            // section reads as the app never having looked.
                            duplicates.isEmpty() -> Text(
                                stringResource(R.string.statistics_no_duplicates),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                            )

                            else -> for (group in duplicates) {
                                DuplicateRow(group = group, onGarmentOpened = onGarmentOpened)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One group of garments that look like each other.
 *
 * The photos, because that is the claim being made and a reader can settle it at a
 * glance in a way no description would. Tapping one opens it, where retiring and
 * deleting already live -- this screen deliberately has no destructive action of
 * its own, since a second one would have to agree with the first about
 * confirmations and orphaned photos forever.
 */
@Composable
private fun DuplicateRow(group: DuplicateGarmentGroup, onGarmentOpened: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Scrolls sideways, because a group has no ceiling: eight near-identical
        // pairs of socks is an ordinary thing to own, and eight thumbnails are
        // wider than a phone. The other axis from the page, so they do not fight.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            for (garment in group.garments) {
                // Named, through the resource the lifespan rows already use: three
                // photos all described as "open this garment" tell a screen reader
                // nothing about which one it is on.
                val what = garment.subcategory?.takeIf { it.isNotBlank() }
                    ?.let { garmentTypeLabel(it) }
                    ?: categoryLabel(garment.category)

                AsyncImage(
                    model = garment.displayImage,
                    contentDescription = stringResource(R.string.statistics_open_garment, what),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .testTag(duplicateTag(garment.id))
                        // The description names it; a click label as well would
                        // have a screen reader say the same words twice.
                        .clickable { onGarmentOpened(garment.id) },
                )
            }
        }

        Text(
            // The count, and no "why". Every group is here for the identical
            // reason -- same type, same colours -- so a reason line would repeat
            // the section's own heading once per row.
            pluralStringResource(R.plurals.statistics_duplicate_count, group.garments.size, group.garments.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What to call a garment: its type, or its category when no type is recorded.
 *
 * The same fallback the duplicate rows use, asked for often enough on a gap card
 * -- the garment wanted, the one retired, and every tied alternative -- to be
 * worth naming once.
 */
@Composable
private fun typeLabel(category: String, subcategory: String?): String =
    subcategory?.takeIf { it.isNotBlank() }?.let { garmentTypeLabel(it) }
        ?: categoryLabel(category)

/**
 * One gap: what is missing, why that is the answer, and the outfits it would
 * finish.
 *
 * The outfits are the point of the card rather than decoration. "You are missing
 * black work shoes" is an assertion; the same claim with three outfits made of
 * the reader's own clothes and one empty frame in them is something they can
 * check at a glance -- and the empty frame is why those outfits are not simply
 * suggestions, since it is the one thing in them they cannot put on.
 *
 * This is also as close as the app comes to the outfit card it deliberately does
 * not build. The photos are a row of thumbnails, not a composed flat-lay: the
 * same decision, for the same reason, and nothing here reads as a picture of an
 * outfit because nothing here is trying to be one.
 */
@Composable
private fun GapRow(gap: GapWithPhotos, onAdd: (PhantomGarment) -> Unit) {
    val want = gap.gap.want
    val type = typeLabel(want.category, want.subcategory)

    // The colour is named only when the palette has a name for it. A hex reads as
    // a serial number in the middle of a sentence, and the frame below is already
    // filled with the colour -- so the honest fallback is to show it rather than
    // spell it.
    val colourName = paletteColorFor(want.colorPrimary)?.first?.let { paletteLabel(it) }
    val title = if (colourName != null) {
        stringResource(R.string.gap_want, colourName, type)
    } else {
        type
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(gapTag(gap.gap.slot)),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

        Text(
            when (gap.gap.evidence) {
                // Names the garment rather than the slot, because this claim is
                // about something that actually happened to this wardrobe and the
                // reader will remember the shoes.
                GapEvidence.RETIRED_UNREPLACED -> stringResource(
                    R.string.gap_because_retired,
                    typeLabel(
                        gap.replaces?.category ?: want.category,
                        gap.replaces?.subcategory,
                    ),
                )

                // The occasion leads the sentence as a label rather than being
                // dropped into the middle of one. "A work day" reads well in
                // English and its Spanish equivalent does not -- "un dia trabajo"
                // is not a phrase -- and no amount of lowercasing fixes a language
                // where the noun has to be declined to sit there.
                GapEvidence.NOTHING_FITS -> stringResource(
                    R.string.gap_because_nothing_fits,
                    stringResource(gap.gap.occasion.labelRes),
                )

                GapEvidence.RAISES_THE_BAR -> stringResource(R.string.gap_because_better)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Exact, and from the counting rather than from the sampling below it, so
        // it is not capped by how many outfits happened to be drawn.
        Text(
            pluralStringResource(
                R.plurals.gap_unlocks,
                gap.gap.outfitsUnlocked.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                gap.gap.outfitsUnlocked,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        for (example in gap.examples) {
            GapExample(example = example, colour = want.colorPrimary, ghostLabel = title)
        }

        // Only when the arithmetic genuinely could not choose. Naming one type as
        // the answer where three tied would be showing the order of the catalogue
        // as advice.
        if (gap.gap.alternatives.isNotEmpty()) {
            // Resolved before they are joined: `joinToString` takes its transform
            // as a nullable parameter, so it cannot be inlined and nothing
            // composable may be called inside it. `map` can.
            val alternatives = gap.gap.alternatives.map {
                typeLabel(it.category, it.subcategory)
            }

            Text(
                stringResource(R.string.gap_alternatives, alternatives.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The whole point of the section being actionable: the gap closes into the
        // add form, already filled in with what it just spent all that work
        // deciding. Anything less and the reader has to retype the answer.
        Button(
            onClick = { onAdd(want) },
            modifier = Modifier.padding(top = 2.dp),
        ) { Text(stringResource(R.string.gap_add)) }
    }
}

/**
 * One outfit the gap would finish: real photographs, and one empty frame.
 *
 * Scrolls sideways for the same reason a duplicate group does -- an outfit can be
 * four or five garments and the thumbnails are wider than a phone -- and on the
 * other axis from the page, so the two do not fight.
 */
@Composable
private fun GapExample(example: GapOutfit, colour: String, ghostLabel: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        for (garment in example.garments) {
            if (garment == null) {
                GhostTile(colour = colour, label = ghostLabel)
            } else {
                AsyncImage(
                    model = garment.displayImage,
                    // Null rather than a description: these are the outfit's
                    // supporting cast, already counted in the line above, and a
                    // screen reader announcing four garments per example three
                    // times over would bury the one thing the card is saying.
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
    }
}

/**
 * The garment that is not there.
 *
 * Dashed rather than solid, and filled with the colour being suggested rather
 * than with a placeholder grey: among photographs of real clothes an outline
 * reads as absence in a way no icon does, and the fill is what makes it "a black
 * one goes here" rather than "something goes here".
 *
 * Faded, because at full strength a flat rectangle of colour beside photographs
 * looks like a garment photographed against itself.
 */
@Composable
private fun GhostTile(colour: String, label: String) {
    val outline = MaterialTheme.colorScheme.outline
    val fill = colour.toComposeColor() ?: MaterialTheme.colorScheme.surfaceVariant
    val description = stringResource(R.string.gap_ghost, label)

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(fill.copy(alpha = 0.35f))
            .drawBehind {
                drawRoundRect(
                    color = outline,
                    cornerRadius = CornerRadius(6.dp.toPx()),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(5.dp.toPx(), 4.dp.toPx()),
                        ),
                    ),
                )
            }
            // Described, unlike the photographs beside it: this is the one tile in
            // the row that is the answer rather than the evidence for it.
            .semantics { contentDescription = description },
    )
}

/**
 * One section's title, and the thing you tap to see it.
 *
 * The whole row is the target rather than the chevron alone, which is the only way
 * a title is a comfortable thing to hit with a thumb.
 */
@Composable
private fun SectionHeader(
    title: String,
    open: Boolean,
    onClick: () -> Unit,
    hint: String? = null,
) {
    val label = stringResource(
        if (open) R.string.statistics_section_collapse else R.string.statistics_section_expand
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            // Only while the section is open: a hint about tapping rows is noise
            // next to rows that are not on screen.
            if (hint != null && open) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // One glyph that turns, not two that swap. A swap is instant however long
        // the section takes to open, so the arrow arrives before the thing it is
        // describing; a rotation lasts as long as the opening does.
        val turn by animateFloatAsState(
            targetValue = if (open) 180f else 0f,
            animationSpec = springGentle(),
            label = "section-chevron",
        )

        Icon(
            Glyph.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.graphicsLayer { rotationZ = turn },
        )
    }
}

/** A garment's type if it has one, else its category -- the same rule as the list. */
@Composable
private fun LifespanBar.label(): String =
    entry.subcategories.firstOrNull()?.let { garmentTypeLabel(it) }
        ?: categoryLabel(entry.category)

@Composable
private fun Chart(bars: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { bars() }
    }
}

/** How far behind its predecessor each bar starts growing. The design's 60ms. */
private const val BAR_STAGGER_MILLIS = 60L

@Composable
private fun Tile(
    label: String,
    value: Long,
    // Before the link, because lint requires the modifier to be the first optional
    // parameter a composable takes -- and it is right: every other composable in
    // this app is called that way.
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val clickLabel = stringResource(R.string.statistics_open_wardrobe)

    Card(
        modifier = if (onClick == null) {
            modifier
        } else {
            modifier.clickable(onClickLabel = clickLabel, onClick = onClick)
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "$value",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * One category's breakdown, indented under it.
 *
 * The bars are scaled against this group's own largest rather than the category
 * chart's -- the module's decision -- so opening a small category shows a spread
 * rather than four slivers.
 */
@Composable
private fun Subcategories(
    category: String,
    bars: List<StatBar>,
    onLinkRequested: (WardrobeLink?) -> Unit,
) {
    val rule = MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp)
            // A rule down the left, drawn rather than laid out: a sibling Box
            // sized with fillMaxHeight measures to nothing inside a list item,
            // where the height is unbounded, and Modifier.border draws all four
            // sides.
            .drawBehind { drawRect(color = rule, size = Size(2.dp.toPx(), size.height)) }
            .padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (bars.isEmpty()) {
            Text(
                "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            for ((index, bar) in bars.withIndex()) {
                val type = bar.subcategoryName(category)

                BarRow(
                    label = bar.subcategoryLabel(category),
                    fraction = bar.fraction,
                    index = index,
                    value = "${bar.count}",
                    labelWidth = 88.dp,
                    fill = MaterialTheme.colorScheme.primaryContainer,
                    height = 14.dp,
                    // Nothing to offer on the row for garments with no type
                    // recorded: "no type" is not something the filter can ask for,
                    // and a button that filtered by nothing would show the whole
                    // category and look broken.
                    action = if (type == null) {
                        null
                    } else {
                        {
                            ShowInWardrobe(
                                label = bar.subcategoryLabel(category),
                                tag = statFilterTag(bar.key),
                                onClick = { onLinkRequested(WardrobeLink.Type(category, type)) },
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun BarRow(
    label: String,
    fraction: Double,
    value: String,
    swatch: (@Composable () -> Unit)? = null,
    /** Degrees for a trailing chevron, or null for a row that opens nothing. */
    chevronTurn: Float? = null,
    onClick: (() -> Unit)? = null,
    clickLabel: String? = null,
    /** A control at the end of the row, outside whatever [onClick] does. */
    action: (@Composable () -> Unit)? = null,
    /** Where in its chart this row sits, which is how far behind the first it grows. */
    index: Int = 0,
    labelWidth: Dp = 96.dp,
    valueWidth: Dp = 32.dp,
    fill: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 20.dp,
) {
    // Grown into place rather than appearing at full length, which makes the
    // comparison between bars easier to read as the chart settles -- and one
    // after another, sixty milliseconds apart, so the eye is walked down the
    // chart in the order the rows are sorted in.
    //
    // The flag is what makes this happen on *every* open rather than once.
    // `animateFloatAsState` starts at whatever it is first handed, so a panel
    // recomposed from scratch when its section is expanded would draw every bar
    // at full length with nothing to animate from. Starting at zero and flipping
    // a frame later is the whole trick.
    var grown by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(index * BAR_STAGGER_MILLIS)
        grown = true
    }

    val width by animateFloatAsState(
        targetValue = if (grown) fraction.toFloat() else 0f,
        animationSpec = springGentle(),
        label = "bar",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier.clickable(onClickLabel = clickLabel, onClick = onClick)
                }
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.width(labelWidth),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (swatch != null) {
                swatch()
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .height(height)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // A zero-width bar draws nothing at all, which is the honest picture
            // of a row with nothing in it.
            if (width > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(width)
                        .fillMaxHeight()
                        .background(fill),
                )
            }
        }

        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(valueWidth),
        )

        if (chevronTurn != null) {
            val turn by animateFloatAsState(
                targetValue = chevronTurn,
                animationSpec = springGentle(),
                label = "row-chevron",
            )

            Icon(
                Glyph.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = turn },
            )
        }

        action?.invoke()
    }
}

/**
 * "Open that garment."
 *
 * The same size and place as [ShowInWardrobe], because it sits in the same column
 * of rows, and a different glyph because it does a different thing: a lifespan bar
 * is one garment, not a group, so there is nothing to filter a list down to.
 */
@Composable
private fun OpenGarment(label: String, tag: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp).testTag(tag)) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = stringResource(R.string.statistics_open_garment, label),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * "Show me these in the wardrobe."
 *
 * Small, because it sits at the end of every row in a chart and a chart of eight
 * categories should not read as a column of buttons. The wardrobe tab's own glyph,
 * so what it opens is recognisable before it is tapped, and it names its category
 * for a screen reader -- eight buttons all called "show in wardrobe" would be
 * eight identical announcements.
 */
@Composable
private fun ShowInWardrobe(label: String, tag: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(28.dp).testTag(tag),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.List,
            contentDescription = stringResource(R.string.statistics_show_in_wardrobe, label),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * The colour beside its bar.
 *
 * The many-coloured sentinel is drawn as four quadrants rather than skipped: a
 * garment recorded that way has no one hex to show, and an omitted swatch would
 * make the row look like a colour that failed to parse -- which is a different
 * thing, and is what the empty ring below means.
 */
@Composable
private fun Swatch(swatch: String) {
    val shape = Modifier.size(16.dp).clip(CircleShape)

    if (swatch == MULTI_SWATCH) {
        Column(modifier = shape) {
            Row(modifier = Modifier.weight(1f)) {
                Quadrant(Color(0xFFFF0000))
                Quadrant(Color(0xFFFFD700))
            }
            Row(modifier = Modifier.weight(1f)) {
                Quadrant(Color(0xFF0066CC))
                Quadrant(Color(0xFF228B22))
            }
        }
        return
    }

    val color = swatch.toComposeColor()
    Box(
        modifier = if (color == null) {
            shape.background(MaterialTheme.colorScheme.surfaceVariant)
        } else {
            shape.background(color)
        }
    )
}

@Composable
private fun RowScope.Quadrant(color: Color) {
    Box(modifier = Modifier.weight(1f).fillMaxHeight().background(color))
}

/**
 * A palette colour by its name, and anything else by the value stored.
 *
 * The sentinel is named here rather than looked up: the palette is keyed by hex,
 * and `multi` is not one.
 */
/**
 * The palette key a colour bar belongs to, or null if it is not in the palette.
 *
 * Separate from the wording so the rule stays testable without an Android
 * resource table: what the palette answers is arithmetic on strings, and what the
 * reader sees is a lookup.
 *
 * The sentinel is named directly because it is not a hex, and the palette is
 * keyed by hex.
 */
internal fun ColorBar.paletteKey(): String? =
    if (swatch == MULTI_SWATCH) MULTI_SWATCH else paletteColorFor(swatch)?.first

/** A palette colour by name, and anything else by the value stored. */
@Composable
internal fun ColorBar.colorLabel(): String = paletteKey()?.let { paletteLabel(it) } ?: key

/**
 * A subcategory's own name, without the category the module prefixed it with.
 *
 * The prefix is what keeps "boots" under footwear distinct from "boots"
 * elsewhere; the reader is already looking at the category, so the label drops
 * it.
 */
/**
 * The garment type a subcategory bar stands for, without the category the module
 * prefixed it with -- or null where no type was recorded.
 *
 * Pure, for the same reason as [paletteKey]: the prefix rule is the part that goes
 * wrong silently, and it is worth testing where no resources exist.
 */
internal fun StatBar.subcategoryName(category: String): String? {
    val name = key.removePrefix("$category:")
    return if (name == NO_SUBCATEGORY) null else name
}

@Composable
internal fun StatBar.subcategoryLabel(category: String): String =
    subcategoryName(category)?.let { garmentTypeLabel(it) }
        ?: stringResource(R.string.statistics_no_subcategory)

