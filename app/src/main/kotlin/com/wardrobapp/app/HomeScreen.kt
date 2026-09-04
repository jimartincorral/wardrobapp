package com.wardrobapp.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Where the app opens: what you own, and the way to everywhere else.
 *
 * The fourth tab, which brings the bar to the four the app this replaced has.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeViewModel.State,
    onAddRequested: () -> Unit,
    onWardrobeRequested: () -> Unit,
    onArchivedRequested: () -> Unit,
    onOutfitsRequested: () -> Unit,
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
                    // Both open the wardrobe, because a number you are looking at
                    // is the obvious way in to the things it counts. Archived opens
                    // it showing retired garments: the plain wardrobe hides every
                    // one of them, so a link that did not ask for them would answer
                    // a tap on "12 archived" with a list containing none of them.
                    Count(
                        label = stringResource(R.string.home_items),
                        value = state.countText(state.items),
                        onClick = onWardrobeRequested,
                        clickLabel = stringResource(R.string.home_open_wardrobe),
                        modifier = Modifier.weight(1f),
                    )
                    Count(
                        label = stringResource(R.string.home_archived),
                        value = state.countText(state.archived),
                        onClick = onArchivedRequested,
                        clickLabel = stringResource(R.string.home_open_archived),
                        modifier = Modifier.weight(1f),
                    )
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
                val press = remember { MutableInteractionSource() }

                Button(
                    onClick = onAddRequested,
                    interactionSource = press,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .height(CTA_HEIGHT)
                        .pressScale(press),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(R.string.home_add_garment),
                        style = ctaLabel(),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            item {
                Action(
                    title = stringResource(R.string.home_outfits_title),
                    // Not "AI-powered", which is what the app this replaced calls
                    // it: the suggestions come from the pair scores it learns
                    // from your own ratings, which is a better thing to say
                    // about them and is what the app actually does.
                    detail = stringResource(R.string.home_outfits_detail),
                    glyph = Glyph.AutoAwesome,
                    onClick = onOutfitsRequested,
                )
            }

            // One card where there were two. "Analytics" and "Statistics" were
            // the same question asked twice, and they are one page now.
            item {
                Action(
                    title = stringResource(R.string.home_statistics_title),
                    detail = stringResource(R.string.home_statistics_detail),
                    glyph = Glyph.Insights,
                    onClick = onStatisticsRequested,
                )
            }

            item {
                Action(
                    title = stringResource(R.string.home_settings_title),
                    detail = stringResource(R.string.home_settings_detail),
                    glyph = null,
                    onClick = onSettingsRequested,
                )
            }
        }
    }
}

/**
 * The height every filled call to action in the app is.
 *
 * Fifty-two rather than Material's forty. The design sets its button labels two
 * points above the default, and at forty-four the taller of the two lines was
 * being clipped by the button's own bounds -- which looks like a font bug and is
 * a box that is too short.
 */
internal val CTA_HEIGHT = 52.dp

/** 500 15/22, the design's filled-button label. Two points over Material's own. */
@Composable
internal fun ctaLabel() = MaterialTheme.typography.labelLarge.copy(
    fontSize = 15.sp,
    lineHeight = 22.sp,
    fontWeight = FontWeight.Medium,
)

@Composable
private fun Count(
    label: String,
    value: String,
    onClick: () -> Unit,
    clickLabel: String,
    modifier: Modifier = Modifier,
) {
    // The whole card, not the number: a tap target the size of two digits is a tap
    // target nobody hits, and the label is as much a name for the thing as the
    // count is.
    val press = remember { MutableInteractionSource() }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(16.dp),
        // Lifted rather than shrunk. The two sit side by side, and one that
        // scales down opens a gap between the pair that reads as the row moving
        // rather than as the card being pressed.
        modifier = modifier
            .pressLift(press)
            .clickable(
                interactionSource = press,
                indication = null,
                onClickLabel = clickLabel,
                onClick = onClick,
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 28.sp,
                    lineHeight = 36.sp,
                ),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A row that leads somewhere.
 *
 * The glyph in front is decorative -- the title beside it says the same thing in
 * words -- so it names nothing to a screen reader, and the chevron is the same: a
 * row that announced "Statistics, insights, chevron right" would be reading its
 * own furniture aloud.
 *
 * [glyph] is null for Settings, which is the one destination Material's core set
 * already carries an icon for.
 */
@Composable
private fun Action(title: String, detail: String, glyph: Painter?, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }

    Card(
        shape = RoundedCornerShape(16.dp),
        // Nudged right rather than scaled: a full-width row that shrinks pulls its
        // own edges in from the screen's, which reads as the card resizing. Three
        // dp towards where the tap is going says the same thing and stays put.
        modifier = Modifier
            .fillMaxWidth()
            .pressNudge(press)
            .clickable(interactionSource = press, indication = null, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                val tint = MaterialTheme.colorScheme.onPrimaryContainer

                if (glyph == null) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Icon(
                        glyph,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Icon(
                Glyph.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
