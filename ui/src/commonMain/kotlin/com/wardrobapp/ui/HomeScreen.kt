package com.wardrobapp.ui

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
import com.wardrobapp.ui.resources.outfits_suggest
import com.wardrobapp.ui.resources.home_recent_title
import com.wardrobapp.ui.resources.home_open_wardrobe_action
import com.wardrobapp.presentation.garmentCaptionFor
import com.wardrobapp.presentation.GarmentCaption
import com.wardrobapp.data.GarmentRecord
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wardrobapp.presentation.FirstStep
import com.wardrobapp.presentation.FirstSteps
import com.wardrobapp.presentation.HomeScreenState
import com.wardrobapp.ui.resources.Res
import com.wardrobapp.ui.resources.action_retry
import com.wardrobapp.ui.resources.count_unknown
import com.wardrobapp.ui.resources.error_wardrobe_unreadable
import com.wardrobapp.ui.resources.home_add_garment
import com.wardrobapp.ui.resources.home_archived
import com.wardrobapp.ui.resources.home_items
import com.wardrobapp.ui.resources.home_open_archived
import com.wardrobapp.ui.resources.home_open_wardrobe
import com.wardrobapp.ui.resources.home_outfits_detail
import com.wardrobapp.ui.resources.home_outfits_title
import com.wardrobapp.ui.resources.home_settings_detail
import com.wardrobapp.ui.resources.home_settings_title
import com.wardrobapp.ui.resources.home_statistics_detail
import com.wardrobapp.ui.resources.home_statistics_title
import com.wardrobapp.ui.resources.home_subtitle
import com.wardrobapp.ui.resources.home_title
import org.jetbrains.compose.resources.stringResource

/**
 * Where the app opens: what you own, and the way to everywhere else.
 *
 * The fourth tab, which brings the bar to the four the app this replaced has.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeScreenState,
    /**
     * The first-steps card, or null when it does not belong here -- dismissed,
     * every job done, or a wardrobe that arrived whole from a backup. Whether
     * that is so is [com.wardrobapp.presentation.FirstSteps]'s answer, not this
     * screen's.
     */
    firstSteps: FirstSteps?,
    onFirstStepsDismissed: () -> Unit,
    onFirstStep: (FirstStep) -> Unit,
    onAddRequested: () -> Unit,
    onWardrobeRequested: () -> Unit,
    onArchivedRequested: () -> Unit,
    onOutfitsRequested: () -> Unit,
    onStatisticsRequested: () -> Unit,
    onSettingsRequested: () -> Unit,
    onRetry: () -> Unit,
    /**
     * The newest garments still in use, for the desktop's "Recently added" row.
     * The phone's Home has no room for it and is never given any.
     */
    recent: List<GarmentRecord> = emptyList(),
    /** What a recent garment's cell says under its photo: the wardrobe's own choice. */
    caption: GarmentCaption = garmentCaptionFor(null),
    onRecentOpened: (String) -> Unit = {},
    /**
     * A couple of outfit suggestions, for the desktop's "Outfit ideas": a slot,
     * because they come with an outfits model of their own (see OutfitIdeas).
     * Null on the phone, whose Home links to the outfits tab instead.
     */
    outfitIdeas: (@Composable () -> Unit)? = null,
) {
    if (isExpanded()) {
        ExpandedHome(
            state = state,
            firstSteps = firstSteps,
            onFirstStepsDismissed = onFirstStepsDismissed,
            onFirstStep = onFirstStep,
            onAddRequested = onAddRequested,
            onWardrobeRequested = onWardrobeRequested,
            onArchivedRequested = onArchivedRequested,
            onOutfitsRequested = onOutfitsRequested,
            onStatisticsRequested = onStatisticsRequested,
            onSettingsRequested = onSettingsRequested,
            onRetry = onRetry,
            recent = recent,
            caption = caption,
            onRecentOpened = onRecentOpened,
            outfitIdeas = outfitIdeas,
        )
        return
    }

    Scaffold(topBar = { HomeTopBar() }) { insets ->
        LazyColumn(
            modifier = Modifier.padding(insets),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Above the subtitle and the counts, because it is the only thing on
            // this screen with work in it: everything below says what the wardrobe
            // is, and this says what it does not have yet.
            if (firstSteps != null) {
                item {
                    FirstStepsCard(
                        steps = firstSteps,
                        onDismiss = onFirstStepsDismissed,
                        onStep = onFirstStep,
                    )
                }
            }

            item {
                Text(
                    stringResource(Res.string.home_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Counts(state, onWardrobeRequested, onArchivedRequested)
                }
            }

            // Read once into a local: the state lives in :presentation now, and
            // Kotlin will not smart-cast a property from another module, which
            // could in principle answer differently the second time it is asked.
            val error = state.error
            if (error != null) {
                item { Unreadable(error, onRetry) }
            }

            item {
                AddGarmentButton(onAddRequested, Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }

            item {
                Action(
                    title = stringResource(Res.string.home_outfits_title),
                    // Not "AI-powered", which is what the app this replaced calls
                    // it: the suggestions come from the pair scores it learns
                    // from your own ratings, which is a better thing to say
                    // about them and is what the app actually does.
                    detail = stringResource(Res.string.home_outfits_detail),
                    glyph = Glyph.AutoAwesome,
                    onClick = onOutfitsRequested,
                )
            }

            // One card where there were two. "Analytics" and "Statistics" were
            // the same question asked twice, and they are one page now.
            item {
                Action(
                    title = stringResource(Res.string.home_statistics_title),
                    detail = stringResource(Res.string.home_statistics_detail),
                    glyph = Glyph.Insights,
                    onClick = onStatisticsRequested,
                )
            }

            item {
                Action(
                    title = stringResource(Res.string.home_settings_title),
                    detail = stringResource(Res.string.home_settings_detail),
                    glyph = null,
                    onClick = onSettingsRequested,
                )
            }
        }
    }
}

/**
 * The two counts, side by side, for whichever row holds them.
 *
 * The phone's row is these two alone; the desktop's adds the add button after
 * them, which is why this is the cards and not the row.
 */
@Composable
private fun RowScope.Counts(state: HomeScreenState, onWardrobeRequested: () -> Unit, onArchivedRequested: () -> Unit) {
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
        label = stringResource(Res.string.home_items),
        value = state.countText(state.items),
        onClick = onWardrobeRequested,
        clickLabel = stringResource(Res.string.home_open_wardrobe),
        modifier = Modifier.weight(1f),
    )
    Count(
        label = stringResource(Res.string.home_archived),
        value = state.countText(state.archived),
        onClick = onArchivedRequested,
        clickLabel = stringResource(Res.string.home_open_archived),
        modifier = Modifier.weight(1f),
    )
}

/** "Could not read the wardrobe", with the error and a retry. */
@Composable
private fun Unreadable(error: String, onRetry: () -> Unit) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(Res.string.error_wardrobe_unreadable),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
        }
    }
}

/** The filled "Add a garment": the one call to action Home has, at either width. */
@Composable
private fun AddGarmentButton(onAddRequested: () -> Unit, modifier: Modifier = Modifier) {
    val press = remember { MutableInteractionSource() }

    Button(
        onClick = onAddRequested,
        interactionSource = press,
        modifier = modifier.height(CTA_HEIGHT).pressScale(press).clickCursor(),
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(
            stringResource(Res.string.home_add_garment),
            style = ctaLabel(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * Home on a desktop-width window: a dashboard rather than a column of links.
 *
 * The phone's Home is a way to everywhere else, which on a monitor the rail
 * already is. So the left two thirds show the wardrobe itself -- what was added
 * last, and a couple of outfits made from it -- and the right third keeps the
 * shortcuts the rail does not have words for. The outfits shortcut is the one
 * dropped: the ideas beside it are the outfits tab, already open.
 */
@Composable
private fun ExpandedHome(
    state: HomeScreenState,
    firstSteps: FirstSteps?,
    onFirstStepsDismissed: () -> Unit,
    onFirstStep: (FirstStep) -> Unit,
    onAddRequested: () -> Unit,
    onWardrobeRequested: () -> Unit,
    onArchivedRequested: () -> Unit,
    onOutfitsRequested: () -> Unit,
    onStatisticsRequested: () -> Unit,
    onSettingsRequested: () -> Unit,
    onRetry: () -> Unit,
    recent: List<GarmentRecord>,
    caption: GarmentCaption,
    onRecentOpened: (String) -> Unit,
    outfitIdeas: (@Composable () -> Unit)?,
) {
    Scaffold(topBar = { HomeTopBar() }) { insets ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            // Two to one, with the shortcuts never narrower than 320dp: below
            // that their one-line descriptions wrap to three.
            val content = minOf(maxWidth, 1320.dp)
            val side = maxOf(320.dp, (content - 24.dp) / 3)

            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.Top,
                modifier = Modifier.width(content),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(Res.string.home_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Counts(state, onWardrobeRequested, onArchivedRequested)
                        AddGarmentButton(onAddRequested, Modifier.weight(1.3f))
                    }

                    state.error?.let { Unreadable(it, onRetry) }

                    // Left out until there is something in it. An empty wardrobe
                    // already says so in the count above, and in the first-steps
                    // card beside it.
                    if (recent.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 20.dp),
                        ) {
                            Text(
                                stringResource(Res.string.home_recent_title),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onWardrobeRequested) {
                                Text(stringResource(Res.string.home_open_wardrobe_action))
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            for (garment in recent.take(RECENT_ACROSS)) {
                                GarmentCell(
                                    garment,
                                    caption = caption,
                                    modifier = Modifier.weight(1f),
                                ) { onRecentOpened(garment.id) }
                            }
                            // Six columns whatever the count, so three new garments
                            // are drawn the size six would be.
                            repeat(RECENT_ACROSS - recent.size.coerceAtMost(RECENT_ACROSS)) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    if (outfitIdeas != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(top = 20.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(Res.string.home_outfits_title),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    stringResource(Res.string.home_outfits_detail),
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            OutlinedButton(
                                onClick = onOutfitsRequested,
                                contentPadding = PaddingValues(start = 16.dp, end = 24.dp),
                                modifier = Modifier.height(40.dp).clickCursor(),
                            ) {
                                Icon(Glyph.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(
                                    stringResource(Res.string.outfits_suggest),
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }

                        outfitIdeas()
                    }
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    // Down by the subtitle's height, so the first card lines up with
                    // the counts rather than with a line of grey text.
                    modifier = Modifier.width(side).padding(top = 32.dp),
                ) {
                    if (firstSteps != null) {
                        FirstStepsCard(steps = firstSteps, onDismiss = onFirstStepsDismissed, onStep = onFirstStep)
                    }

                    Action(
                        title = stringResource(Res.string.home_statistics_title),
                        detail = stringResource(Res.string.home_statistics_detail),
                        glyph = Glyph.Insights,
                        onClick = onStatisticsRequested,
                    )
                    Action(
                        title = stringResource(Res.string.home_settings_title),
                        detail = stringResource(Res.string.home_settings_detail),
                        glyph = null,
                        onClick = onSettingsRequested,
                    )
                }
            }
        }
    }
}

/** How many recently added garments the desktop's Home shows: one row of the wardrobe's smallest size. */
private const val RECENT_ACROSS = 6

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar() {
    TopAppBar(title = { Text(stringResource(Res.string.home_title)) })
}

/**
 * The height every filled call to action in the app is.
 *
 * Fifty-two rather than Material's forty. The design sets its button labels two
 * points above the default, and at forty-four the taller of the two lines was
 * being clipped by the button's own bounds -- which looks like a font bug and is
 * a box that is too short.
 */
val CTA_HEIGHT = 52.dp

/** 500 15/22, the design's filled-button label. Two points over Material's own. */
@Composable
fun ctaLabel() = MaterialTheme.typography.labelLarge.copy(
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
            .clickCursor()
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
            .clickCursor()
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
private fun HomeScreenState.countText(value: Long): String =
    if (loading || error != null) stringResource(Res.string.count_unknown) else "$value"
