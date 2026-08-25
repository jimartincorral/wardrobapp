package com.wardrobapp.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wardrobapp.presentation.MAX_RATING

/**
 * One star, by the rating it gives.
 *
 * Five identical glyphs otherwise: a test asking for "☆" matches all of them and
 * can only pick one by position, which is the kind of assertion that passes on a
 * row drawn backwards.
 */
fun starTag(rating: Int) = "star-$rating"

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
 */
@Composable
internal fun Stars(rating: Int?, onRate: (Int) -> Unit) {
    Row {
        for (star in 1..MAX_RATING) {
            val filled = rating != null && star <= rating

            Text(
                if (filled) "★" else "☆",
                style = MaterialTheme.typography.headlineSmall,
                color = if (filled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .testTag(starTag(star))
                    .clickable { onRate(star) }
                    .padding(horizontal = 2.dp),
            )
        }
    }
}
