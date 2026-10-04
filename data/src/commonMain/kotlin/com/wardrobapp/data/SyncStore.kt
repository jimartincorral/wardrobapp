package com.wardrobapp.data

/**
 * One side of a sync: the wardrobe in this database, read as a
 * [WardrobeSnapshot] and brought up to a merged one. See WardrobeSync.kt for
 * what a sync is; this is the part that touches tables.
 *
 * The phone and the server each have one of these over their own database,
 * and run the same code against the same schema, so neither side applies a
 * merge differently from the other.
 */
class SyncStore(private val driver: SqlDriver) {

    /**
     * Everything this side has, in the form a sync exchanges.
     *
     * Garments are read through the same normalization every screen reads them
     * through, so what is sent is the garment as this app understands it, and
     * photo references are reduced to their stored names so the other side can
     * resolve them against its own photo directory.
     */
    fun snapshot(): WardrobeSnapshot = WardrobeSnapshot(
        garments = driver.query("SELECT * FROM garments ORDER BY id")
            .map { row -> normalizeGarmentRow(row, imageDirectory = "").withStoredPhotoNames() },
        outfits = driver.query("SELECT * FROM outfits ORDER BY id").map { row ->
            SyncOutfit(
                id = jsString(row["id"] ?: ""),
                name = jsString(row["name"] ?: ""),
                garmentIds = parseStringArray(row["garment_ids"]),
                occasion = row["occasion"] as? String,
                season = row["season"] as? String,
                createdAt = jsString(row["created_at"] ?: ""),
                updatedAt = row["updated_at"] as? String,
                isSuggested = jsTruthy(row["is_suggested"]),
                isPinned = jsTruthy(row["is_pinned"]),
                isArchived = jsTruthy(row["is_archived"]),
            )
        },
        // The latest per outfit: earlier builds appended a row per star tap,
        // and the newest of those is the one OutfitQueries.rating reads.
        ratings = driver.query("SELECT * FROM outfit_ratings ORDER BY rated_at")
            .map { row ->
                SyncRating(
                    id = jsString(row["id"] ?: ""),
                    outfitId = jsString(row["outfit_id"] ?: ""),
                    rating = (row["rating"] as Number).toInt(),
                    feedback = row["feedback"] as? String,
                    ratedAt = jsString(row["rated_at"] ?: ""),
                )
            }
            .associateBy { it.outfitId }
            .values
            .sortedBy { it.outfitId },
        deletions = driver.query("SELECT * FROM deletions ORDER BY kind, id").mapNotNull { row ->
            val kind = DeletionKind.entries.firstOrNull { it.storedName == row["kind"] } ?: return@mapNotNull null
            Deletion(kind = kind, id = jsString(row["id"] ?: ""), deletedAt = jsString(row["deleted_at"] ?: ""))
        },
    )

    /**
     * Merge [theirs] into this wardrobe: read it, merge, and apply, as one
     * transaction, so nothing written here between the read and the write --
     * an edit in the browser, another phone's sync -- is overwritten by a
     * merge that never saw it.
     *
     * The server answers a phone with this. The phone uses it too, on the
     * wardrobe the server answers with, rather than applying that answer as
     * it stands: the phone may have changed something while the request was
     * out, and merging again keeps that change, to be sent next time, where
     * applying the answer blindly would lose it. Merging an already-merged
     * wardrobe again is harmless; that is what idempotent means.
     */
    fun mergeWith(theirs: WardrobeSnapshot): MergedWardrobe = driver.transaction {
        val ours = snapshot()
        val merged = merge(ours, theirs)
        val changes = changesTo(ours, merged)
        MergedWardrobe(
            merged = merged,
            photosNoLongerUsed = apply(changes),
            changedAnything = !changes.isEmpty,
        )
    }

    /**
     * Make [theirs] this wardrobe, rather than merge it in: what the server
     * does when a phone restores a backup and sends what it restored.
     *
     * Everything here that [theirs] does not have is deleted, as of [now], and
     * the deletions are kept so other phones delete it too when they next
     * sync; everything [theirs] has is taken as it comes. The phone stamps
     * every restored row with the time of the restore before sending it (see
     * [stampAll]), so a later sync from another phone does not bring back the
     * edits the restore was meant to replace -- an edit made after the restore
     * still wins, as it should.
     *
     * A merge in every other respect: the same rules, the same application,
     * the same transaction, so a restore and a sync from somebody else cannot
     * interleave.
     */
    fun replaceWith(theirs: WardrobeSnapshot, now: String): MergedWardrobe = driver.transaction {
        val ours = snapshot()
        val keptGarments = theirs.garments.map { it.id }.toSet()
        val keptOutfits = theirs.outfits.map { it.id }.toSet()
        val gone = ours.garments.filter { it.id !in keptGarments }.map { Deletion(DeletionKind.GARMENT, it.id, now) } +
            ours.outfits.filter { it.id !in keptOutfits }.map { Deletion(DeletionKind.OUTFIT, it.id, now) }
        val merged = merge(ours, theirs.copy(deletions = theirs.deletions + gone))
        val changes = changesTo(ours, merged)
        MergedWardrobe(
            merged = merged,
            photosNoLongerUsed = apply(changes),
            changedAnything = !changes.isEmpty,
        )
    }

    /**
     * Mark every garment and outfit here as changed at [now], and forget what
     * was deleted: what a restore on a phone that syncs does to the wardrobe
     * it has just put back.
     *
     * Without it the restored rows keep the times they had when the backup was
     * made, every edit made anywhere since is newer, and the next sync quietly
     * undoes the restore. With it, the restored wardrobe is the newest thing
     * anyone did. The deletions go because they are the backup's, not this
     * phone's: the server works out what to delete itself (see [replaceWith]).
     */
    fun stampAll(now: String) {
        driver.transaction {
            driver.execute("UPDATE garments SET updated_at = ?", listOf(now))
            driver.execute("UPDATE outfits SET updated_at = ?", listOf(now))
            driver.execute("DELETE FROM deletions")
        }
    }

    /**
     * Make this side's wardrobe the merged one, in one transaction, so a sync
     * that fails partway leaves the wardrobe as it was rather than half-merged.
     *
     * Returns the photos nothing here refers to any more -- the photos of
     * garments removed, and those a changed garment stopped using -- for the
     * caller to delete, as GarmentWrites.delete leaves files to its caller.
     *
     * Ratings go through OutfitWrites.rate, the path a rating made here takes,
     * so what a rating made on the other side teaches is learned here too: the
     * learned scores are not copied, because they are a running average of the
     * ratings each side has seen in the order it saw them, and two of those
     * cannot be merged into one. Each side learns from every rating instead.
     */
    fun apply(changes: SnapshotChanges): List<String> = driver.transaction {
        val noLongerUsed = mutableListOf<String>()

        for (garment in changes.garments) {
            val before = photosOf(garment.id)
            writeGarment(garment)
            noLongerUsed += before - garment.photoNames().toSet()
        }

        for (outfit in changes.outfits) writeOutfit(outfit)

        for (removed in changes.removed) {
            when (removed.kind) {
                DeletionKind.GARMENT -> {
                    noLongerUsed += photosOf(removed.id)
                    driver.execute("DELETE FROM garments WHERE id = ?", listOf(removed.id))
                    driver.execute(
                        "DELETE FROM garment_pair_scores WHERE garment_id_a = ? OR garment_id_b = ?",
                        listOf(removed.id, removed.id),
                    )
                    driver.execute("DELETE FROM garment_scores WHERE garment_id = ?", listOf(removed.id))
                }
                DeletionKind.OUTFIT -> {
                    driver.execute("DELETE FROM outfit_ratings WHERE outfit_id = ?", listOf(removed.id))
                    driver.execute("DELETE FROM outfits WHERE id = ?", listOf(removed.id))
                }
            }
        }

        // After the outfits, since rating one reads it to know what to learn.
        val outfits = OutfitWrites(driver)
        for (rating in changes.ratings) {
            outfits.rate(
                ratingId = rating.id,
                outfitId = rating.outfitId,
                rating = rating.rating,
                feedback = rating.feedback,
                now = rating.ratedAt,
            )
        }

        // The merged wardrobe's deletions, exactly: one this side remembered
        // for a garment the merge brought back is forgotten, since a later
        // edit has already outvoted it.
        driver.execute("DELETE FROM deletions")
        for (deletion in changes.deletions) {
            driver.execute(
                "INSERT INTO deletions (kind, id, deleted_at) VALUES (?, ?, ?)",
                listOf(deletion.kind.storedName, deletion.id, deletion.deletedAt),
            )
        }

        noLongerUsed.filter { it.isNotEmpty() }.distinct()
    }

    /**
     * Write a whole garment row as the merge has it, its timestamps included:
     * a merged garment keeps the time of the change that won, not the time it
     * arrived, or the next sync would read the copy as a newer edit.
     *
     * Updated, and inserted only if there was nothing to update, rather than
     * `INSERT OR REPLACE`: replacing is a delete and an insert, and the delete is
     * what foreign keys cascade on. The same for outfits below, whose rating
     * would go with it.
     */
    private fun writeGarment(garment: GarmentRecord) {
        val columns = listOf(
            "image_uri" to toStoredImageRef(garment.imageUri),
            "image_uri_nobg" to garment.imageUriNoBg?.let(::toStoredImageRef),
            "image_uris" to jsonArray(garment.imageUris.map(::toStoredImageRef)),
            "image_uris_nobg" to jsonArray(garment.imageUrisNoBg.map(::toStoredImageRef)),
            "category" to garment.category,
            "subcategory" to garment.subcategories.firstOrNull(),
            "subcategories" to jsonArray(garment.subcategories),
            "tags" to jsonArray(garment.tags),
            "brand" to garment.brand,
            "color_primary" to garment.colorPrimary,
            "color_secondary" to garment.colorSecondary,
            "color_palette" to jsonArray(garment.colorPalette),
            "size" to garment.size,
            "purchase_date" to garment.purchaseDate,
            "is_available" to if (garment.isAvailable) 1 else 0,
            "unavailable_date" to garment.unavailableDate,
            // NOT NULL on a fresh install. A garment old enough to have neither
            // gets the time of its last change, which is all anyone knows.
            "created_at" to (garment.createdAt ?: garment.changedAt),
            "updated_at" to garment.changedAt,
        )
        upsert("garments", garment.id, columns)
    }

    private fun writeOutfit(outfit: SyncOutfit) {
        upsert(
            "outfits",
            outfit.id,
            listOf(
                "name" to outfit.name,
                "garment_ids" to jsonArray(outfit.garmentIds),
                "occasion" to outfit.occasion,
                "season" to outfit.season,
                "created_at" to outfit.createdAt,
                "updated_at" to outfit.updatedAt,
                "is_suggested" to if (outfit.isSuggested) 1 else 0,
                "is_pinned" to if (outfit.isPinned) 1 else 0,
                "is_archived" to if (outfit.isArchived) 1 else 0,
            ),
        )
    }

    /** Update the row with [id] to [columns], or insert it if there is none. */
    private fun upsert(table: String, id: String, columns: List<Pair<String, Any?>>) {
        val updated = driver.execute(
            "UPDATE $table SET ${columns.joinToString(", ") { "${it.first} = ?" }} WHERE id = ?",
            columns.map { it.second } + id,
        )
        if (updated == 0) {
            driver.execute(
                "INSERT INTO $table (id, ${columns.joinToString(", ") { it.first }}) " +
                    "VALUES (?, ${columns.joinToString(", ") { "?" }})",
                listOf(id) + columns.map { it.second },
            )
        }
    }

    /** The stored names of the photos the garment row [id] refers to now. */
    private fun photosOf(id: String): List<String> = driver
        .query("SELECT * FROM garments WHERE id = ?", listOf(id))
        .firstOrNull()
        ?.let { normalizeGarmentRow(it, imageDirectory = "").photoNames() }
        ?: emptyList()
}

/** Every photo a garment refers to, by stored name. */
fun GarmentRecord.photoNames(): List<String> =
    (imageUris + imageUrisNoBg + listOfNotNull(imageUri, imageUriNoBg))
        .map(::toStoredImageRef)
        .filter { it.isNotEmpty() }
        .distinct()

private fun GarmentRecord.withStoredPhotoNames() = copy(
    imageUri = toStoredImageRef(imageUri),
    imageUriNoBg = imageUriNoBg?.let(::toStoredImageRef),
    imageUris = imageUris.map(::toStoredImageRef),
    imageUrisNoBg = imageUrisNoBg.map(::toStoredImageRef),
)

/**
 * A merge, applied: the wardrobe both sides now have, the files this side can
 * let go, and whether anything here changed -- which is what tells the phone's
 * screens whether what they are showing is still true.
 */
data class MergedWardrobe(
    val merged: WardrobeSnapshot,
    val photosNoLongerUsed: List<String>,
    val changedAnything: Boolean,
)
