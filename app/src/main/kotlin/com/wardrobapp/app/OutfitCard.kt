package com.wardrobapp.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.presentation.CARD_ASPECT_HEIGHT
import com.wardrobapp.presentation.CARD_ASPECT_WIDTH
import com.wardrobapp.presentation.CardGarment
import com.wardrobapp.presentation.outfitCardLayout

/** The composed card, so a test can find it without matching a photo. */
const val OUTFIT_CARD = "outfit-card"

/**
 * An outfit as one picture.
 *
 * Where each garment goes is [outfitCardLayout]'s answer, in fractions of the
 * card; this multiplies them by whatever room it has been given. The image that
 * gets shared is drawn from the same answer by [outfitCardBitmap], which is what
 * makes the file a picture *of this card* rather than a second arrangement that
 * happens to contain the same clothes.
 *
 * Photos are drawn cropped to fill their share. A garment photo is already 3:4,
 * and a band is not, so something has to give -- and a cropped garment reads as a
 * flat-lay while a letterboxed one reads as a mistake.
 */
@Composable
fun OutfitCard(garments: List<GarmentRecord>, modifier: Modifier = Modifier) {
    val placements = remember(garments) { outfitCardLayout(garments.map { it.asCardGarment() }) }

    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(CARD_ASPECT_WIDTH.toFloat() / CARD_ASPECT_HEIGHT)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag(OUTFIT_CARD),
    ) {
        // An outfit of garments that fill no slot -- all underwear, or a row from a
        // restored backup whose garments are gone. Saying so beats a blank card
        // that reads as a photo still loading.
        if (placements.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.outfit_card_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@BoxWithConstraints
        }

        for (placement in placements) {
            AsyncImage(
                model = placement.garment.imageUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .offset(
                        x = maxWidth * placement.x.toFloat(),
                        y = maxHeight * placement.y.toFloat(),
                    )
                    .size(
                        width = maxWidth * placement.width.toFloat(),
                        height = maxHeight * placement.height.toFloat(),
                    ),
            )
        }
    }
}

/**
 * What the layout needs to know about a garment.
 *
 * The cut-out where there is one, because a cut-out garment on a plain ground is
 * what makes a composition read as a flat-lay rather than as a collage of
 * snapshots.
 */
internal fun GarmentRecord.asCardGarment() = CardGarment(
    id = id,
    imageUri = displayImage,
    category = category,
    subcategory = subcategory,
)
