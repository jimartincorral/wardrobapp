package com.wardrobapp.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wardrobapp.presentation.AnalyticsView
import com.wardrobapp.presentation.CategoryBar
import com.wardrobapp.presentation.LifespanBar

/**
 * The wardrobe in numbers.
 *
 * Layout only: every bar arrives with its length already decided, clamped into
 * its track, and in the order the query returned.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    state: AnalyticsViewModel.State,
    onStatisticsRequested: () -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.analytics_title)) }) }) { insets ->
        val view = state.view

        when {
            state.loading && view == null -> Box(
                modifier = Modifier.fillMaxWidth().padding(insets).padding(48.dp),
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

            else -> Body(view, insets, onStatisticsRequested)
        }
    }
}

@Composable
private fun Body(
    view: AnalyticsView,
    insets: PaddingValues,
    onStatisticsRequested: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.padding(insets),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Summary(stringResource(R.string.analytics_in_use), view.totalItems, Modifier.weight(1f))
                Summary(stringResource(R.string.analytics_retired), view.archivedItems, Modifier.weight(1f))
            }
        }

        if (view.isEmpty) {
            item {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.analytics_empty_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.analytics_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        // Where the colour, brand and subcategory breakdowns live, as in the app
        // that ships: this tab answers "how much and how long", and the detail is
        // one tap away rather than on the same page.
        item {
            Card(modifier = Modifier.clickable(onClick = onStatisticsRequested)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.analytics_open_statistics), style = MaterialTheme.typography.bodyLarge)
                    Text("\u203a", style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        item { Text(stringResource(R.string.analytics_by_category), style = MaterialTheme.typography.titleMedium) }

        item {
            Chart(view.categories.isEmpty(), stringResource(R.string.analytics_no_garments)) {
                for (bar in view.categories) {
                    BarRow(label = bar.heading(), fraction = bar.fraction, value = "${bar.value}")
                }
            }
        }

        item { Text(stringResource(R.string.analytics_lifespan), style = MaterialTheme.typography.titleMedium) }

        item {
            Chart(
                view.lifespans.isEmpty(),
                stringResource(R.string.analytics_no_lifespan),
            ) {
                for (bar in view.lifespans) {
                    BarRow(
                        label = bar.label(),
                        fraction = bar.fraction,
                        value = stringResource(R.string.analytics_days, bar.value),
                    )
                }
            }
        }
    }
}

@Composable
private fun Summary(label: String, value: Long, modifier: Modifier = Modifier) {
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
            )
        }
    }
}

@Composable
private fun Chart(isEmpty: Boolean, emptyText: String, bars: @Composable () -> Unit) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isEmpty) {
                Text(
                    emptyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                )
            } else {
                bars()
            }
        }
    }
}

@Composable
private fun BarRow(label: String, fraction: Double, value: String) {
    // Grown into place rather than appearing at full length, which makes the
    // comparison between bars easier to read as the chart settles.
    val width by animateFloatAsState(targetValue = fraction.toFloat(), label = "bar")

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(96.dp),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // A zero-width bar draws nothing at all, which is the honest picture
            // of a category with nothing in it.
            if (width > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(width)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }

        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp).padding(start = 8.dp),
        )
    }
}

/** The port has a translation table now, so this reads from it. */
@Composable
private fun CategoryBar.heading(): String = categoryLabel(category)

/** A garment's type if it has one, else its category -- the same rule as the list. */
@Composable
private fun LifespanBar.label(): String =
    entry.subcategories.firstOrNull()?.let { garmentTypeLabel(it) }
        ?: categoryLabel(entry.category)
