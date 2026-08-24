package com.wardrobapp.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Where the app opens: what you own, and the way to everywhere else.
 *
 * The fourth tab, which brings the bar to the four the app that ships has. Worth
 * having beyond parity for one reason: Statistics is reached from here and from
 * the Analytics tab, and until this existed the port had one route to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeViewModel.State,
    onAddRequested: () -> Unit,
    onOutfitsRequested: () -> Unit,
    onAnalyticsRequested: () -> Unit,
    onStatisticsRequested: () -> Unit,
    onSettingsRequested: () -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("My Wardrobe") }) }) { insets ->
        LazyColumn(
            modifier = Modifier.padding(insets),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Keep track of everything you wear.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // A dash rather than a zero while the counts are unknown: a
                    // zero here is a real answer, and "your wardrobe is empty" is
                    // the wrong thing to say about a read that has not finished
                    // or has failed.
                    Count("Items", state.countText(state.items), Modifier.weight(1f))
                    Count("Archived", state.countText(state.archived), Modifier.weight(1f))
                }
            }

            if (state.error != null) {
                item {
                    Card {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Couldn't read the wardrobe",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                state.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            TextButton(onClick = onRetry) { Text("Try again") }
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = onAddRequested,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) { Text("Add a garment") }
            }

            item {
                Action(
                    title = "Outfit ideas",
                    // Not "AI-powered", which is what the app that ships calls
                    // it: the suggestions come from the pair scores it learns
                    // from your own ratings, which is a better thing to say
                    // about them and is what both apps actually do.
                    detail = "Put together from what you own",
                    onClick = onOutfitsRequested,
                )
            }

            item {
                Action(
                    title = "Analytics",
                    detail = "How much you own, and how long things last",
                    onClick = onAnalyticsRequested,
                )
            }

            item {
                Action(
                    title = "Statistics",
                    detail = "Counts by category, colour and brand",
                    onClick = onStatisticsRequested,
                )
            }

            item {
                Action(
                    title = "Settings",
                    detail = "Back up your wardrobe, or restore one",
                    onClick = onSettingsRequested,
                )
            }
        }
    }
}

@Composable
private fun Count(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                value,
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
private fun Action(title: String, detail: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("›", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** A count once it is known, and a dash until then. */
private fun HomeViewModel.State.countText(value: Long): String =
    if (loading || error != null) "—" else "$value"
