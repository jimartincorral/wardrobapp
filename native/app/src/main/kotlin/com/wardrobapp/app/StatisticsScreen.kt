package com.wardrobapp.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wardrobapp.presentation.BrandSort
import com.wardrobapp.presentation.ColorBar
import com.wardrobapp.presentation.MULTI_SWATCH
import com.wardrobapp.presentation.NO_SUBCATEGORY
import com.wardrobapp.presentation.StatBar
import com.wardrobapp.presentation.StatisticsView
import com.wardrobapp.presentation.paletteColorFor

/**
 * What the wardrobe is made of.
 *
 * Layout and words only: every bar arrives with its length already decided by
 * :presentation, which is held to the React Native screen's answers by
 * `statistics-view.jsonl`. What is left here is what that module deliberately
 * does not own -- turning keys into text, and drawing a swatch.
 *
 * This is also the screen `AnalyticsQueries.byColor`, `byBrand` and
 * `bySubcategory` were written for. All three have been tested and unrendered
 * since :data was written, because the analytics tab shows neither.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    state: StatisticsViewModel.State,
    onBack: () -> Unit,
    onCategoryTapped: (String) -> Unit,
    onBrandSortChanged: (BrandSort) -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Statistics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { insets ->
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
                Text("Couldn't read the wardrobe", style = MaterialTheme.typography.titleMedium)
                state.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                TextButton(onClick = onRetry) { Text("Try again") }
            }

            else -> Body(
                view = view,
                expanded = state.expanded,
                brandSort = state.brandSort,
                insets = insets,
                onCategoryTapped = onCategoryTapped,
                onBrandSortChanged = onBrandSortChanged,
            )
        }
    }
}

@Composable
private fun Body(
    view: StatisticsView,
    expanded: Set<String>,
    brandSort: BrandSort,
    insets: PaddingValues,
    onCategoryTapped: (String) -> Unit,
    onBrandSortChanged: (BrandSort) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.padding(insets),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "A breakdown of what you own.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile("Items", view.total, Modifier.weight(1f))
                Tile("Categories", view.distinctCategories.toLong(), Modifier.weight(1f))
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile("Colours", view.distinctColors.toLong(), Modifier.weight(1f))
                Tile("Brands", view.distinctBrands.toLong(), Modifier.weight(1f))
            }
        }

        if (view.isEmpty) {
            // Every chart below would be an empty card, so on an empty wardrobe
            // the tiles and one explanation are the whole page.
            item {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Nothing to measure yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Add a few garments and this fills in: what your wardrobe is made " +
                                "of, which colours you actually wear, and who made it all.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            return@LazyColumn
        }

        if (view.categories.isNotEmpty()) {
            item { SectionHeader("By category", hint = "Tap a category to see what's in it") }

            item {
                Chart {
                    for (bar in view.categories) {
                        val isOpen = bar.key in expanded

                        BarRow(
                            label = bar.key.sentence(),
                            fraction = bar.fraction,
                            value = "${bar.count}",
                            chevron = if (isOpen) "▾" else "▸",
                            onClick = { onCategoryTapped(bar.key) },
                            // What tapping does, for a screen reader, which the
                            // chevron only says visually.
                            clickLabel = if (isOpen) "Hide subcategories" else "Show subcategories",
                        )

                        if (isOpen) {
                            Subcategories(
                                category = bar.key,
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

        if (view.colors.isNotEmpty()) {
            item { SectionHeader("By colour") }

            item {
                Chart {
                    for (bar in view.colors) {
                        BarRow(
                            label = bar.colorLabel(),
                            fraction = bar.fraction,
                            value = "${bar.count}",
                            swatch = { Swatch(bar.swatch) },
                        )
                    }
                }
            }
        }

        if (view.brands.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("By brand", style = MaterialTheme.typography.titleMedium)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = brandSort == BrandSort.COUNT,
                            onClick = { onBrandSortChanged(BrandSort.COUNT) },
                            label = { Text("Most") },
                        )
                        FilterChip(
                            selected = brandSort == BrandSort.ALPHA,
                            onClick = { onBrandSortChanged(BrandSort.ALPHA) },
                            label = { Text("A-Z") },
                        )
                    }
                }
            }

            item {
                Chart {
                    for (bar in view.brands) {
                        // A brand is what the wearer typed, so it is shown as
                        // typed rather than capitalized.
                        BarRow(label = bar.key, fraction = bar.fraction, value = "${bar.count}")
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, hint: String? = null) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Chart(bars: @Composable () -> Unit) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { bars() }
    }
}

@Composable
private fun Tile(label: String, value: Long, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
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
private fun Subcategories(category: String, bars: List<StatBar>) {
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
            for (bar in bars) {
                BarRow(
                    label = bar.subcategoryLabel(category),
                    fraction = bar.fraction,
                    value = "${bar.count}",
                    labelWidth = 88.dp,
                    fill = MaterialTheme.colorScheme.primaryContainer,
                    height = 14.dp,
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
    chevron: String? = null,
    onClick: (() -> Unit)? = null,
    clickLabel: String? = null,
    labelWidth: Dp = 104.dp,
    fill: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 20.dp,
) {
    // Grown into place rather than appearing at full length, which makes the
    // comparison between bars easier to read as the chart settles.
    val width by animateFloatAsState(targetValue = fraction.toFloat(), label = "bar")

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
            modifier = Modifier.width(32.dp),
        )

        if (chevron != null) {
            Text(
                chevron,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(20.dp),
            )
        }
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
internal fun ColorBar.colorLabel(): String {
    if (swatch == MULTI_SWATCH) return "Multi"
    return paletteColorFor(swatch)?.first?.paletteWording() ?: key
}

/**
 * A palette key as the garment detail already words it: "lightBlue" becomes
 * "Light blue".
 *
 * Its own copy rather than a shared one, deliberately: the detail screen's is
 * file-private, and a module-wide version would be a second candidate for that
 * name in the file that already declares it.
 */
private fun String.paletteWording(): String =
    replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replaceFirstChar { it.uppercase() }

/**
 * A subcategory's own name, without the category the module prefixed it with.
 *
 * The prefix is what keeps "boots" under footwear distinct from "boots"
 * elsewhere; the reader is already looking at the category, so the label drops
 * it.
 */
internal fun StatBar.subcategoryLabel(category: String): String {
    val name = key.removePrefix("$category:")
    return if (name == NO_SUBCATEGORY) "Not specified" else name.sentence()
}

private fun String.sentence(): String = replace('-', ' ').replaceFirstChar { it.uppercase() }
