package com.wardrobapp.presentation

import com.wardrobapp.data.InspirationRecord

/*
 * An outfit from a photo of somebody wearing it -- a mirror selfie, mostly.
 *
 * The question is the look screen's turned round. A saved look asks "can I
 * wear that?"; a photo of yourself asks "which of my things is this?", so
 * that what was worn becomes an outfit without picking each garment out of
 * the wardrobe by hand. The reading (Gemini Nano listing what is worn) and
 * the matching (recreateLook) are the same either way, so this is the same
 * screen model over a different source rather than a second one: what
 * differs is only where the photo comes from and that it is not kept.
 *
 * A piece that matches nothing is, here, a garment that has not been added
 * yet rather than one to go and buy, and the add button on its row is the
 * same; the screen words it that way (LookScreen's `wearing`).
 */

/** The id the worn photo goes by inside the look screen model; there is only ever one. */
const val WORN_LOOK_ID = "worn"

/**
 * The look screen's source over one photo that is not a saved look: [photo]
 * stands as the look, its reading is kept only as long as this object is,
 * and everything else -- the wardrobe, the reader, saving the outfit -- is
 * [wardrobe]'s, as it is for a saved look.
 *
 * Not kept in the database because the photo is not kept either: it was
 * taken to say what was worn, and once it has, an outfit is what is worth
 * keeping. A saved look is different -- it is kept to steer the suggestions,
 * and its reading is kept with it.
 *
 * No vectors: the style model's resemblance between a whole photo of
 * somebody and each garment breaks ties in a look worth copying, but here
 * the garments are the very ones in the photo, and a tie is better broken by
 * nothing than by how much a selfie resembles a shoe.
 */
class WornPhotoSource(private val wardrobe: LookSource, private val photo: String) : LookSource by wardrobe {

    private var pieces: List<LookPiece>? = null

    override suspend fun look(id: String): InspirationRecord =
        InspirationRecord(id = WORN_LOOK_ID, imageUri = photo, createdAt = "")

    override suspend fun storedPieces(id: String): List<LookPiece>? = pieces

    override suspend fun keepPieces(id: String, pieces: List<LookPiece>) {
        this.pieces = pieces
    }

    override suspend fun vectors(id: String): Pair<FloatArray?, Map<String, FloatArray>> = null to emptyMap()
}
