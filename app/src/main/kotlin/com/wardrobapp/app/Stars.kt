package com.wardrobapp.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import com.wardrobapp.presentation.MAX_RATING
import kotlinx.coroutines.delay

/**
 * One star, by the rating it gives.
 *
 * Five identical glyphs otherwise: a matcher for the star icon matches all of
 * them and can only pick one by position, which is the kind of assertion that
 * passes on a row drawn backwards.
 */
fun starTag(rating: Int) = "star-$rating"

/** How long a tapped star stays large. The design's 360ms. */
private const val POP_MILLIS = 360L

/**
 * A row of stars, tappable.
 *
 * Shared rather than duplicated per screen: the outfits list and an outfit's
 * detail both offer it, and a fix to one copy that missed the other would leave
 * the same control behaving two ways.
 *
 * A null rating means unrated, which is not the same as rated zero -- the row
 * cannot produce a zero, so an unrated outfit shows five empty stars rather than
 * a filled-to-nothing bar.
 *
 * Icons rather than the two text glyphs this used to draw. `★` and `☆` are not
 * the same weight as each other in every font the phone might be running, so a
 * three-star rating could read as three heavy shapes beside two hairlines that
 * happened to be a different size -- and a screen reader announced the character
 * rather than the rating. The filled one comes from Material's core set, the
 * outlined one is vendored beside it.
 *
 * The pop is what makes a rating feel given rather than recorded: the star
 * tapped and every star left of it swell to 1.35 and settle back, so the gesture
 * lands on the *rating* rather than on the one glyph under the thumb. Held here
 * rather than by the caller because it is the row's own animation -- a caller
 * that forgot to clear it would leave five stars stuck large.
 */
@Composable
internal fun Stars(rating: Int?, onRate: (Int) -> Unit) {
    var popped by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(popped) {
        if (popped != null) {
            delay(POP_MILLIS)
            popped = null
        }
    }

    Row {
        for (star in 1..MAX_RATING) {
            val filled = rating != null && star <= rating
            val popping = popped?.let { star <= it } == true

            val scale by animateFloatAsState(
                targetValue = if (popping) 1.35f else 1f,
                animationSpec = springPop(),
                label = "star-pop",
            )

            // A 36dp target round a 26dp glyph. Five stars at glyph size is five
            // targets a thumb cannot tell apart, and the row is the one control
            // on this card that is meant to be tapped precisely.
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .testTag(starTag(star))
                    .clickable {
                        popped = star
                        onRate(star)
                    },
                contentAlignment = Alignment.Center,
            ) {
                // The description is the rating this star gives, not the shape:
                // "three stars" is what the control does.
                val description = pluralStringResource(R.plurals.outfit_rate_stars, star, star)
                val tint = if (filled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                val glyph = Modifier
                    .size(26.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }

                // Two calls rather than one with the icon chosen inside it: the
                // filled star is an `ImageVector` from Material's core set and the
                // outline is a vendored `Painter`, and `Icon` takes one or the
                // other.
                if (filled) {
                    Icon(Icons.Filled.Star, description, glyph, tint)
                } else {
                    Icon(Glyph.StarBorder, description, glyph, tint)
                }
            }
        }
    }
}
