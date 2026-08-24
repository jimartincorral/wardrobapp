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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Where the app opens: what you own, and the way to everywhere else.
 *
 * The fourth tab, which brings the bar to the four the app this replaced has. Worth
 * having for one reason: Statistics is reached from here and from
 * the Analytics tab, and before this screen existed there was one route to it.
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
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.home_title)) }) }) { insets ->
        LazyColumn(
            modifier = Modifier.padding(insets),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.home_subtitle),
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
                    Count(stringResource(R.string.home_items), state.countText(state.items), Modifier.weight(1f))
                    Count(stringResource(R.string.home_archived), state.countText(state.archived), Modifier.weight(1f))
                }
            }

            if (state.error != null) {
                item {
                    Card {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.error_wardrobe_unreadable),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                state.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = onAddRequested,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) { Text(stringResource(R.string.home_add_garment)) }
            }

            item {
                Action(
                    title = stringResource(R.string.home_outfits_title),
                    // Not "AI-powered", which is what the app this replaced calls
                    // it: the suggestions come from the pair scores it learns
                    // from your own ratings, which is a better thing to say
                    // about them and is what the app actually does.
                    detail = stringResource(R.string.home_outfits_detail),
                    onClick = onOutfitsRequested,
                )
            }

            item {
                Action(
                    title = stringResource(R.string.home_analytics_title),
                    detail = stringResource(R.string.home_analytics_detail),
                    onClick = onAnalyticsRequested,
                )
            }

            item {
                Action(
                    title = stringResource(R.string.home_statistics_title),
                    detail = stringResource(R.string.home_statistics_detail),
                    onClick = onStatisticsRequested,
                )
            }

            item {
                Action(
                    title = stringResource(R.string.home_settings_title),
                    detail = stringResource(R.string.home_settings_detail),
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

/**
 * A count once it is known, and a dash until then.
 *
 * Composable now that the dash is a resource: it is language-neutral today, but
 * leaving one literal behind would mean "no literals remain" stops being a thing
 * anyone can check by grepping.
 */
@Composable
private fun HomeViewModel.State.countText(value: Long): String =
    if (loading || error != null) stringResource(R.string.count_unknown) else "$value"
