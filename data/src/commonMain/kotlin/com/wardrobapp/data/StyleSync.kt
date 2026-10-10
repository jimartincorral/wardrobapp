package com.wardrobapp.data

import kotlinx.serialization.Serializable

/**
 * What a phone and the Home Assistant app exchange about style, beside the
 * wardrobe: the looks the reader saved, and the vectors the server's model
 * computed.
 *
 * A route of its own (see SyncRoutes.STYLE) rather than fields on
 * WardrobeSnapshot, because the snapshot is read with a parser that refuses a
 * field it does not know: a field added there would cut every older phone
 * off from a newer server, where a route an older phone never asks for costs
 * it nothing and a newer phone asking an older server gets a 404 it treats
 * as "nothing to say".
 *
 * Looks merge the way garments do -- the latest change wins, whole -- with
 * a deletion as a change (see StyleQueries for why a look is deleted by
 * tombstone). Vectors are not merged: the server computes them and the
 * phone takes what it is given, since only the server has the model.
 */

/** A look as the sync carries it: the photo by its stored name, and when it last changed or was deleted. */
@Serializable
data class SyncInspiration(
    val id: String,
    val photo: String,
    val createdAt: String,
    val updatedAt: String?,
    val deletedAt: String?,
    /** The server's vector for it, where it has one; a phone sends none. */
    val vector: List<Float>? = null,
) {
    val changedAt: String get() = deletedAt ?: updatedAt ?: createdAt
}

/** A garment's vector, from the server's model. */
@Serializable
data class SyncEmbedding(
    val garmentId: String,
    /** The stored photo it was computed from; a phone whose garment has a newer photo wants a newer vector. */
    val photo: String,
    val model: String,
    val vector: List<Float>,
)

/** What a phone sends: its looks, and which garments it already has vectors for, by the photo each came from. */
@Serializable
data class StyleExchange(
    val inspirations: List<SyncInspiration>,
    val embedded: Map<String, String>,
)

/** What the server answers: its looks after the merge, the vectors the phone lacks, and the look photos it wants sent. */
@Serializable
data class StyleAnswer(
    val inspirations: List<SyncInspiration>,
    val embeddings: List<SyncEmbedding>,
    val missingPhotos: List<String>,
)

/** What merging the other side's looks into this side came to. */
data class InspirationMerge(
    /** The looks here now, deleted ones included, for the other side. */
    val merged: List<SyncInspiration>,
    /** Photos of looks this merge deleted here, for the caller to delete. */
    val photosNoLongerUsed: List<String>,
    /** Photos of live looks, for the caller to fetch those it lacks. */
    val photosInUse: List<String>,
    val changedAnything: Boolean,
)

/**
 * [ours] and [theirs] as one list: per id, the one that changed last, a
 * deletion winning a tie since a deletion is the more deliberate act.
 */
fun mergeInspirations(ours: List<SyncInspiration>, theirs: List<SyncInspiration>): List<SyncInspiration> =
    (ours + theirs)
        .groupBy { it.id }
        .values
        .map { versions ->
            versions.sortedWith(
                compareByDescending<SyncInspiration> { it.changedAt }
                    .thenByDescending { it.deletedAt != null }
                    // A vector is worth keeping over none, all else equal.
                    .thenByDescending { it.vector != null },
            ).first()
        }
        .sortedBy { it.id }
