package com.wardrobapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wardrobapp.data.GarmentRecord

/**
 * A garment's photo in a frame, the one way every list, grid and card draws it.
 *
 * Two kinds of picture come through here and they want opposite treatment. A
 * photo as taken has its own background, and the frame crops it: the garment
 * fills the tile edge to edge, and a 3:4 photo in a square frame loses a little
 * of its top and bottom, which is what a thumbnail is. A cut-out -- the same
 * photo with its background removed -- is transparent where the background was,
 * and cropping it does the one thing a cut-out cannot survive: it chops the
 * garment itself, since there is nothing else in the picture to lose. The bag
 * handles and the earrings in the wardrobe list went that way, and what was
 * left floated on the card with ragged edges, because the tile behind it was
 * the same grey as the card.
 *
 * So a cut-out is shown whole, [ContentScale.Fit] with [inset] of breathing
 * room, on a tile that stands apart from whatever the frame sits on, with a
 * hairline round it: a photo has its own edge and a cut-out has none. A plain
 * photo keeps the crop and the quieter tile it always had. The caller says
 * which with [cutout], because the uri alone does not: see
 * [GarmentRecord.displayCutout].
 *
 * The caller owns the frame's size and shape and everything that has to happen
 * before the clip -- a shared-element key, a selection outline, a click -- and
 * passes them as [modifier], in the same order the old inline images used. The
 * clip, the tile and the hairline are this frame's.
 *
 * [photoScale] is how a plain photo meets the frame, and only the detail screen
 * changes it: the one screen whose job is to show the whole garment fits the
 * photo rather than cropping the hem off an imported one that is not 3:4. A
 * cut-out is always fitted, whatever is passed.
 *
 * A null [uri] draws the bare tile, which is what a garment with no photo at all
 * looks like elsewhere already.
 */
@Composable
fun GarmentPhoto(
    uri: String?,
    cutout: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    inset: Dp = 6.dp,
    photoScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (cutout) cutoutSurface() else photoSurface())
            .then(
                if (cutout) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                } else {
                    Modifier
                },
            ),
    ) {
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = contentDescription,
                contentScale = if (cutout) ContentScale.Fit else photoScale,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (cutout) Modifier.padding(inset) else Modifier),
            )
        }
    }
}

/** The garment's [GarmentRecord.displayImage], framed as above. */
@Composable
fun GarmentPhoto(
    garment: GarmentRecord,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    inset: Dp = 6.dp,
    contentDescription: String? = null,
) {
    GarmentPhoto(
        uri = garment.displayImage.takeIf { it.isNotEmpty() },
        cutout = garment.displayCutout != null,
        modifier = modifier,
        shape = shape,
        inset = inset,
        contentDescription = contentDescription,
    )
}
