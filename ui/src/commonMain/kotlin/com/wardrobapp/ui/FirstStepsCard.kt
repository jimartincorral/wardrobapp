package com.wardrobapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wardrobapp.presentation.FirstStep
import com.wardrobapp.presentation.FirstSteps
import com.wardrobapp.ui.resources.Res
import com.wardrobapp.ui.resources.first_steps_bulk
import com.wardrobapp.ui.resources.first_steps_dismiss
import com.wardrobapp.ui.resources.first_steps_garment
import com.wardrobapp.ui.resources.first_steps_open_bulk
import com.wardrobapp.ui.resources.first_steps_open_garment
import com.wardrobapp.ui.resources.first_steps_open_training
import com.wardrobapp.ui.resources.first_steps_rate
import com.wardrobapp.ui.resources.first_steps_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The jobs the first-launch flow does not do, where they can be done.
 *
 * The flow explains the app; this carries the work. It sits at the top of Home
 * rather than being a fourth onboarding screen for one reason: a checklist shown
 * to somebody trying to get into an app is a checklist they tap past, and each of
 * these rows leads somewhere they cannot go until they are in.
 *
 * Nothing here decides anything. Which rows are ticked and whether the card
 * belongs on screen come from [FirstSteps] in :presentation; this renders the
 * answer.
 */
@Composable
internal fun FirstStepsCard(
    steps: FirstSteps,
    onDismiss: () -> Unit,
    onStep: (FirstStep) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag(FIRST_STEPS_CARD),
    ) {
        // Eight at the bottom rather than sixteen: the last row is 48dp of mostly
        // empty space already, and a full pad under it reads as the card having a
        // gap in it.
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.first_steps_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )

                // A word rather than a cross. Dismissing this is permanent, and a
                // 24dp glyph in the corner of a card is the shape of something
                // that can be undone.
                TextButton(onClick = onDismiss) {
                    Text(
                        stringResource(Res.string.first_steps_dismiss),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            // In the order the enum declares them, which is the order they get
            // done in: one garment, then a drawerful, then a rating. A sort here
            // would put "add a garment" under "rate an outfit" for somebody who
            // had rated one, which is advice in the wrong order.
            for (step in FirstStep.entries) {
                StepRow(
                    label = step.labelRes,
                    clickLabel = step.clickLabelRes,
                    done = steps.isDone(step),
                    onClick = { onStep(step) },
                )
            }
        }
    }
}

/** The card, for a test that wants to know whether it is on screen at all. */
const val FIRST_STEPS_CARD = "first-steps-card"

/**
 * One job, ticked or not, and the way to do it.
 *
 * The chevron is decorative, as it is in `Action` on the same screen: the label
 * says where the row goes, so a screen reader announcing "chevron right" would be
 * reading the furniture aloud. The state glyph is decorative for the opposite
 * reason -- it is the row's *answer*, and what it means is carried by the click
 * label rather than by naming the shape.
 */
@Composable
private fun StepRow(
    label: StringResource,
    clickLabel: StringResource,
    done: Boolean,
    onClick: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        // Nudged rather than scaled, as the navigation rows below it are: a
        // full-width row that shrinks pulls its own edges in from the card's,
        // which reads as the row resizing.
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .pressNudge(press)
            .clickable(
                interactionSource = press,
                indication = null,
                onClickLabel = stringResource(clickLabel),
                onClick = onClick,
            ),
    ) {
        // Two calls rather than one with the icon chosen inside: the filled circle
        // is an `ImageVector` from Material's core set and the empty one is a
        // vendored `Painter`, and `Icon` takes one or the other. Same split as the
        // stars.
        if (done) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Icon(
                Glyph.RadioButtonUnchecked,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }

        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )

        Icon(
            Glyph.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * What each row says.
 *
 * Beside the rows rather than in [Vocabulary], which holds the wardrobe's own
 * words -- categories, colours, seasons. These are this card's copy and nothing
 * else reads them.
 */
private val FirstStep.labelRes: StringResource
    get() = when (this) {
        FirstStep.GARMENT -> Res.string.first_steps_garment
        FirstStep.BULK_ADD -> Res.string.first_steps_bulk
        FirstStep.RATE -> Res.string.first_steps_rate
    }

/**
 * Where each row goes, for a screen reader.
 *
 * Separate from the label because the label is the *job* -- "Add your first
 * garment" -- and what a tap does is open a screen. `Count` on the same screen
 * has the same arrangement for the same reason.
 */
private val FirstStep.clickLabelRes: StringResource
    get() = when (this) {
        FirstStep.GARMENT -> Res.string.first_steps_open_garment
        FirstStep.BULK_ADD -> Res.string.first_steps_open_bulk
        FirstStep.RATE -> Res.string.first_steps_open_training
    }
