package com.wardrobapp.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wardrobapp.presentation.TasteTrainingScreenState
import com.wardrobapp.ui.resources.Res
import com.wardrobapp.ui.resources.action_back
import com.wardrobapp.ui.resources.outfits_none_possible
import com.wardrobapp.ui.resources.taste_training_done
import com.wardrobapp.ui.resources.taste_training_exhausted
import com.wardrobapp.ui.resources.taste_training_intro
import com.wardrobapp.ui.resources.taste_training_keep_going
import com.wardrobapp.ui.resources.taste_training_progress
import com.wardrobapp.ui.resources.taste_training_rated
import com.wardrobapp.ui.resources.taste_training_retry
import com.wardrobapp.ui.resources.taste_training_round_done
import com.wardrobapp.ui.resources.taste_training_skip
import com.wardrobapp.ui.resources.taste_training_skipped
import com.wardrobapp.ui.resources.taste_training_title
import com.wardrobapp.ui.resources.taste_training_verdicts
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Training the suggestions: one outfit at a time, rated, ten to a round.
 *
 * The card is the outfits tab's own [SuggestionCard], so an outfit looks the
 * same here as it does there and the stars mean the same thing -- the point of
 * the screen is that these ratings are those ratings, given faster. Above it,
 * "3 of 10" and a bar, as bulk add draws its queue: the one thing a person
 * partway through wants to know is how much is left. Below it, Skip, which
 * teaches nothing and says so by being a text button rather than a star.
 *
 * Advancing slides the rated card out and lands the next from the right, as
 * bulk add does, for the same reason: a card that merely becomes a different
 * card hides the one thing this screen has to make obvious, that a rating was
 * recorded and the queue moved.
 *
 * One centred column at every width. There is nothing to put in a second
 * column beside one card, so the desktop gets the phone's layout with room
 * either side rather than a layout of its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasteTrainingScreen(
    state: TasteTrainingScreenState,
    onBack: () -> Unit,
    onRate: (Int) -> Unit,
    onSkip: () -> Unit,
    onSave: () -> Unit,
    onKeepGoing: () -> Unit,
    onDone: () -> Unit,
    onRetry: () -> Unit,
    onGarmentOpened: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.taste_training_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { insets ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .widthIn(max = CONTENT_WIDTH)
                    .fillMaxWidth()
                    .padding(PaddingValues(16.dp)),
            ) {
                val current = state.current
                when {
                    state.loading -> Loading()
                    state.error != null && current == null -> Failed(state.error!!, onRetry, onDone)
                    state.isEmpty -> NothingToBuild(onDone)
                    current != null -> InProgress(state, onRate, onSkip, onSave, onGarmentOpened)
                    state.isRoundFinished -> Summary(state, onKeepGoing, onDone)
                }
            }
        }
    }
}

@Composable
private fun InProgress(
    state: TasteTrainingScreenState,
    onRate: (Int) -> Unit,
    onSkip: () -> Unit,
    onSave: () -> Unit,
    onGarmentOpened: (String) -> Unit,
) {
    Text(
        stringResource(Res.string.taste_training_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(Res.string.taste_training_progress, state.position + 1, state.round.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(TASTE_TRAINING_PROGRESS),
        )
        LinearProgressIndicator(
            progress = { state.position.toFloat() / state.round.size.coerceAtLeast(1) },
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
            modifier = Modifier.fillMaxWidth().height(4.dp),
        )
    }

    val current = state.current ?: return
    AnimatedContent(
        targetState = current.id,
        transitionSpec = {
            (slideInHorizontally(springGentle()) { it / 3 } + fadeIn(springGentle()))
                .togetherWith(
                    slideOutHorizontally(springGentle()) { -(it * 7) / 10 } +
                        scaleOut(springGentle(), targetScale = 0.8f) +
                        fadeOut(springGentle())
                )
        },
        label = "training-advance",
    ) { id ->
        // Looked up by id rather than captured: the content lambda is kept
        // for the outgoing card too, and the state it sees must be its own.
        val shown = state.round.firstOrNull { it.id == id } ?: current
        SuggestionCard(
            suggestion = shown,
            onSave = onSave,
            // A rating already on its way is not taken again; the stars show
            // the first one until the write answers.
            onRate = { if (!state.writing) onRate(it) },
            onGarmentOpened = onGarmentOpened,
        )
    }

    state.error?.let { error ->
        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onSkip, enabled = !state.writing, modifier = Modifier.testTag(TASTE_TRAINING_SKIP)) {
            Icon(Glyph.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(Res.string.taste_training_skip), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun Summary(state: TasteTrainingScreenState, onKeepGoing: () -> Unit, onDone: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp).testTag(TASTE_TRAINING_SUMMARY),
    ) {
        Text(stringResource(Res.string.taste_training_round_done), style = MaterialTheme.typography.headlineSmall)
        Text(
            pluralStringResource(Res.plurals.taste_training_rated, state.rated, state.rated),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (state.liked > 0 || state.disliked > 0) {
            Text(
                stringResource(Res.string.taste_training_verdicts, state.liked, state.disliked),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.skipped > 0) {
            Text(
                pluralStringResource(Res.plurals.taste_training_skipped, state.skipped, state.skipped),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.exhausted) {
            Text(
                stringResource(Res.string.taste_training_exhausted),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            Button(onClick = onKeepGoing, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(CTA_HEIGHT)) {
                Text(stringResource(Res.string.taste_training_keep_going), style = ctaLabel())
            }
        }
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().height(CTA_HEIGHT)) {
            Text(stringResource(Res.string.taste_training_done), style = ctaLabel())
        }
    }
}

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun NothingToBuild(onDone: () -> Unit) {
    Text(
        stringResource(Res.string.outfits_none_possible),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 24.dp),
    )
    OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().height(CTA_HEIGHT)) {
        Text(stringResource(Res.string.taste_training_done), style = ctaLabel())
    }
}

@Composable
private fun Failed(error: String, onRetry: () -> Unit, onDone: () -> Unit) {
    Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 24.dp))
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(CTA_HEIGHT)) {
        Text(stringResource(Res.string.taste_training_retry), style = ctaLabel())
    }
    OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().height(CTA_HEIGHT)) {
        Text(stringResource(Res.string.taste_training_done), style = ctaLabel())
    }
}

/** The round's "n of 10" line, for the tests. */
const val TASTE_TRAINING_PROGRESS = "taste-training-progress"

/** The Skip button. */
const val TASTE_TRAINING_SKIP = "taste-training-skip"

/** The face shown once a round is done. */
const val TASTE_TRAINING_SUMMARY = "taste-training-summary"

/** One card's worth of width on a desktop; the phone fills what it has. */
private val CONTENT_WIDTH = 560.dp
