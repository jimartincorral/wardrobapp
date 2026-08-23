package com.wardrobapp.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
    onRetry: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Analytics") }) }) { insets ->
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

            else -> Body(view, insets)
        }
    }
}

@Composable
private fun Body(view: AnalyticsView, insets: PaddingValues) {
    LazyColumn(
        modifier = Modifier.padding(insets),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Summary("In use", view.totalItems, Modifier.weight(1f))
                Summary("Retired", view.archivedItems, Modifier.weight(1f))
            }
        }

        if (view.isEmpty) {
            item {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Nothing to measure yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Add a few garments and this fills in: what your wardrobe is made " +
                                "of, and how long the things you retire tend to last.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        item { Text("By category", style = MaterialTheme.typography.titleMedium) }

        item {
            Chart(view.categories.isEmpty(), "Nothing in the wardrobe yet.") {
                for (bar in view.categories) {
                    BarRow(label = bar.categoryLabel(), fraction = bar.fraction, value = "${bar.value}")
                }
            }
        }

        item { Text("How long things lasted", style = MaterialTheme.typography.titleMedium) }

        item {
            Chart(
                view.lifespans.isEmpty(),
                "Nothing retired yet. Mark a garment as no longer in use and it appears here.",
            ) {
                for (bar in view.lifespans) {
                    BarRow(label = bar.label(), fraction = bar.fraction, value = "${bar.value}d")
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

/** A category key as a heading, until the port has a translation table. */
private fun CategoryBar.categoryLabel(): String =
    category.replaceFirstChar { it.uppercase() }

/** A garment's type if it has one, else its category -- the same rule as the list. */
private fun LifespanBar.label(): String =
    entry.subcategories.firstOrNull() ?: entry.category.replaceFirstChar { it.uppercase() }
