package com.wardrobapp.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Two copies of one wardrobe -- the phone's and the Home Assistant server's --
 * made into one again.
 *
 * Each side keeps its wardrobe as it always has, and a sync exchanges all of
 * it: every garment, outfit, rating and remembered deletion, as a
 * [WardrobeSnapshot]. A household's wardrobe is a few hundred rows, small
 * enough to send whole every time, and sending it whole is what lets a sync be
 * this simple: nothing tracks what changed since when, so there is no cursor to
 * lose or get out of step, and a sync that fails halfway is repaired by the
 * next one. Photos are not in the snapshot -- they are files, named once and
 * never renamed, and they travel separately by name.
 *
 * Both sides merge with [merge], which gives the same answer whichever side runs
 * it and whichever copy it is handed first. Then each side applies the
 * difference between what it has and the merged wardrobe; see SyncStore.
 *
 * Phones sync with the server and never with each other, so the server is
 * where every copy meets, one sync at a time, and that order is part of the
 * answer: a garment deleted on one phone is trimmed out of the outfits that
 * held it when its deletion is merged, and an edit that later brings the
 * garment back does not put it back in them -- just as deleting a garment on
 * one phone already removes it from outfits for good. What is guaranteed, and
 * tested in WardrobeSyncTest, is that once every phone has synced after the
 * last change, the phones and the server hold one wardrobe, and syncing again
 * changes nothing.
 *
 * The rule is the one chosen for this app: for each garment, outfit and rating,
 * the latest change wins, whole. "Change" includes deleting: a garment deleted
 * on the phone after it was last edited on the server is deleted on both, and a
 * garment edited after it was deleted somewhere comes back, edit and all,
 * because that edit is the newest thing anybody did to it. Two changes to two
 * different fields of one garment between syncs keep only the newer -- the price
 * of a rule anybody can predict.
 *
 * Times are each device's own clock, as every row has always been stamped. Two
 * clocks that disagree by minutes can pick the wrong winner between two edits
 * made minutes apart on different devices; that is the cost of not having a
 * shared clock, and a household's phone and server are not usually racing.
 */

/** What kind of row a [Deletion] removed. */
@Serializable
enum class DeletionKind {
    @SerialName("garment") GARMENT,
    @SerialName("outfit") OUTFIT,
}

/** A garment or outfit that was deleted, and when -- so a sync deletes it everywhere rather than bringing it back. */
@Serializable
data class Deletion(val kind: DeletionKind, val id: String, val deletedAt: String)

/** An outfit as stored, which is more than a screen is shown: when it last changed. */
@Serializable
data class SyncOutfit(
    val id: String,
    val name: String,
    val garmentIds: List<String>,
    val occasion: String?,
    val season: String?,
    val createdAt: String,
    /** Null for an outfit last changed before the column existed; see [changedAt]. */
    val updatedAt: String?,
    val isSuggested: Boolean,
    val isPinned: Boolean,
    val isArchived: Boolean,
)

/** An outfit's rating. One per outfit, keyed by the outfit. */
@Serializable
data class SyncRating(
    val id: String,
    val outfitId: String,
    val rating: Int,
    val feedback: String?,
    val ratedAt: String,
)

/**
 * Everything a sync exchanges: one side's whole wardrobe.
 *
 * Garments as GarmentRecord, with photo references in the stored form -- bare
 * names -- so a reference means the same file on both sides.
 */
@Serializable
data class WardrobeSnapshot(
    val garments: List<GarmentRecord>,
    val outfits: List<SyncOutfit>,
    val ratings: List<SyncRating>,
    val deletions: List<Deletion>,
)

/** What one side has to do to its own wardrobe to arrive at a merged one. */
data class SnapshotChanges(
    /** Garments to write as they are here: new to this side, or newer than its copy. */
    val garments: List<GarmentRecord>,
    val outfits: List<SyncOutfit>,
    val ratings: List<SyncRating>,
    /** Garments and outfits this side has and should not. */
    val removed: List<Deletion>,
    /** The merged wardrobe's remembered deletions, to keep. */
    val deletions: List<Deletion>,
) {
    val isEmpty: Boolean get() = garments.isEmpty() && outfits.isEmpty() && ratings.isEmpty() && removed.isEmpty()
}

/**
 * When a garment last changed. A row too old to have been stamped on update
 * falls back to its creation, and one too old for either to the beginning of
 * time, which any stamped change beats.
 */
internal val GarmentRecord.changedAt: String get() = updatedAt ?: createdAt ?: ""

internal val SyncOutfit.changedAt: String get() = updatedAt ?: createdAt

/** The two copies, made one. Commutative: `merge(a, b) == merge(b, a)`. */
fun merge(ours: WardrobeSnapshot, theirs: WardrobeSnapshot): WardrobeSnapshot {
    val deletions = latestDeletions(ours.deletions + theirs.deletions)

    // Garments first, since which garments survive decides what is left of the
    // outfits that hold them.
    val garmentDeletions = deletions.filterValues { it.kind == DeletionKind.GARMENT }.mapKeys { it.key.second }
    val ourGarments = ours.garments.associateBy { it.id }
    val theirGarments = theirs.garments.associateBy { it.id }
    val garments = mutableMapOf<String, GarmentRecord>()
    val survivingGarmentDeletions = mutableMapOf<String, Deletion>()
    for (id in ourGarments.keys + theirGarments.keys + garmentDeletions.keys) {
        val row = latest(
            listOfNotNull(ourGarments[id], theirGarments[id]),
            GarmentRecord.serializer(),
        ) { it.changedAt }
        val deletion = garmentDeletions[id]
        // A deletion wins a tie with an edit: when the two are indistinguishable
        // in time, the one that removes something is the one somebody will notice
        // undone.
        if (deletion != null && (row == null || deletion.deletedAt >= row.changedAt)) {
            survivingGarmentDeletions[id] = deletion
        } else if (row != null) {
            garments[id] = row
        }
    }

    val outfitDeletions = deletions.filterValues { it.kind == DeletionKind.OUTFIT }.mapKeys { it.key.second }
    val ourOutfits = ours.outfits.associateBy { it.id }
    val theirOutfits = theirs.outfits.associateBy { it.id }
    val outfits = mutableMapOf<String, SyncOutfit>()
    val survivingOutfitDeletions = mutableMapOf<String, Deletion>()
    for (id in ourOutfits.keys + theirOutfits.keys + outfitDeletions.keys) {
        val versions = listOfNotNull(ourOutfits[id], theirOutfits[id])

        // An outfit holds only garments that still exist, as deleting a garment
        // on either side already makes it -- see OutfitWrites.removeGarment.
        // Done here as well because the deletion and the outfit can come from
        // different sides: the phone deletes a shirt while the server adds it
        // to an outfit, and neither side ever saw both.
        //
        // Each version is trimmed *before* the latest is chosen, not after, and
        // that order is what lets a second sync agree with the first. Two
        // versions stamped at the same moment are told apart by their content;
        // if the content compared were the untrimmed one, the trimmed outfit a
        // merge produced would be compared afresh against the other side's
        // untrimmed copy next time, and could lose to it. Trimmed first, the
        // same versions are compared every time, and the same one wins.
        val trimmed = versions.map { it.copy(garmentIds = it.garmentIds.filter { garment -> garment in garments }) }
        val row = latest(
            trimmed,
            SyncOutfit.serializer(),
            // An outfit that still holds something beats an emptied one stamped
            // at the same moment: a tie should not delete an outfit one side
            // can still wear.
        ) { "${it.changedAt}|${if (it.garmentIds.isEmpty()) 0 else 1}" }
        val deletion = outfitDeletions[id]
        if (deletion != null && (row == null || deletion.deletedAt >= row.changedAt)) {
            survivingOutfitDeletions[id] = deletion
            continue
        }
        if (row == null) continue

        if (row.garmentIds.isNotEmpty()) {
            outfits[id] = row
        } else {
            // Emptied, it goes the way removeGarment sends an emptied outfit:
            // deleted when the last of its garments was -- and never before the
            // version that won, or an older version of the outfit, stamped
            // after the garment went, would outvote the deletion next time.
            // Across every version's garments rather than the winner's alone:
            // two versions can trim to the same empty outfit, and which of them
            // "won" would then depend on which side ran the merge.
            val lastGarmentGone = versions.flatMap { it.garmentIds }
                .mapNotNull { survivingGarmentDeletions[it]?.deletedAt }
                .maxOrNull()
            survivingOutfitDeletions[id] = Deletion(
                kind = DeletionKind.OUTFIT,
                id = id,
                deletedAt = listOfNotNull(lastGarmentGone, row.changedAt).max(),
            )
        }
    }

    // One rating per outfit, the latest, and only for an outfit that is left.
    val ratings = (ours.ratings + theirs.ratings)
        .groupBy { it.outfitId }
        .filterKeys { it in outfits }
        .mapNotNull { (_, candidates) -> latest(candidates, SyncRating.serializer()) { it.ratedAt } }

    return WardrobeSnapshot(
        garments = garments.values.sortedBy { it.id },
        outfits = outfits.values.sortedBy { it.id },
        ratings = ratings.sortedBy { it.outfitId },
        deletions = (survivingGarmentDeletions.values + survivingOutfitDeletions.values)
            .sortedWith(compareBy({ it.kind }, { it.id })),
    )
}

/** What [current] has to change to become [merged]. */
fun changesTo(current: WardrobeSnapshot, merged: WardrobeSnapshot): SnapshotChanges {
    val garments = current.garments.associateBy { it.id }
    val outfits = current.outfits.associateBy { it.id }
    val ratings = current.ratings.associateBy { it.outfitId }
    val mergedGarments = merged.garments.map { it.id }.toSet()
    val mergedOutfits = merged.outfits.map { it.id }.toSet()

    return SnapshotChanges(
        garments = merged.garments.filter { garments[it.id] != it },
        outfits = merged.outfits.filter { outfits[it.id] != it },
        ratings = merged.ratings.filter { ratings[it.outfitId] != it },
        removed = merged.deletions.filter { deletion ->
            when (deletion.kind) {
                DeletionKind.GARMENT -> deletion.id in garments && deletion.id !in mergedGarments
                DeletionKind.OUTFIT -> deletion.id in outfits && deletion.id !in mergedOutfits
            }
        },
        deletions = merged.deletions,
    )
}

/** The latest deletion of each row, by (kind, id). */
private fun latestDeletions(all: List<Deletion>): Map<Pair<DeletionKind, String>, Deletion> =
    all.groupBy { it.kind to it.id }.mapValues { (_, same) -> same.maxWith(compareBy({ it.deletedAt }, { it.id })) }

/**
 * The newest of [candidates] by [changedAt]; between two equally new and
 * different versions, the one whose JSON sorts last. Arbitrary, but the same
 * on both sides, which is what keeps the merge commutative: a tie broken by
 * which side happened to run the merge would give the phone and the server
 * different answers to the same question.
 */
private fun <T> latest(candidates: List<T>, serializer: KSerializer<T>, changedAt: (T) -> String): T? =
    candidates.maxWithOrNull(compareBy<T>(changedAt).thenBy { CANONICAL.encodeToString(serializer, it) })

private val CANONICAL = Json { encodeDefaults = true }

/**
 * Remember that a garment or outfit was deleted, for sync. The latest deletion
 * of a row is the one kept.
 *
 * Two statements rather than SQLite's upsert, `ON CONFLICT ... DO UPDATE`, which
 * arrived in SQLite 3.24 -- and Android 7, which this app still supports, ships
 * 3.9. The same reason SyncStore writes rows by updating and then inserting.
 */
internal fun recordDeletion(driver: SqlDriver, kind: DeletionKind, id: String, now: String) {
    driver.execute(
        "INSERT OR IGNORE INTO deletions (kind, id, deleted_at) VALUES (?, ?, ?)",
        listOf(kind.storedName, id, now),
    )
    driver.execute(
        "UPDATE deletions SET deleted_at = ? WHERE kind = ? AND id = ? AND deleted_at < ?",
        listOf(now, kind.storedName, id, now),
    )
}

internal val DeletionKind.storedName: String
    get() = when (this) {
        DeletionKind.GARMENT -> "garment"
        DeletionKind.OUTFIT -> "outfit"
    }
