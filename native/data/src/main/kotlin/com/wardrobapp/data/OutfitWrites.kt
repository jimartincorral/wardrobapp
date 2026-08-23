package com.wardrobapp.data

import com.wardrobapp.domain.PairScore
import com.wardrobapp.domain.foldRatingIntoPair
import com.wardrobapp.domain.garmentPairs

/**
 * Reading and writing outfits, their ratings, and the pair scores a rating
 * teaches.
 *
 * The learning arithmetic itself lives in `:domain`; this is the storage around
 * it. Rating is transactional, which matters more here than anywhere else: a
 * rating that half-applied would leave the learned scores disagreeing with the
 * rating that produced them, and nothing would ever notice.
 */
class OutfitWrites(private val driver: SqlDriver) {

    fun insert(
        id: String,
        name: String,
        garmentIds: List<String>,
        occasion: String? = null,
        season: String? = null,
        isSuggested: Boolean = false,
        isPinned: Boolean = false,
        now: String,
    ) {
        driver.execute(
            """
            INSERT INTO outfits (id, name, garment_ids, occasion, season, created_at, is_suggested, is_pinned)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(
                id, name, jsonArray(garmentIds), occasion, season, now,
                if (isSuggested) 1 else 0, if (isPinned) 1 else 0,
            ),
        )
    }

    /**
     * Insert an outfit unless one with this id is already there.
     *
     * How a suggestion gets saved. Each suggestion in a batch is given its id
     * when the batch is produced, so saving it -- by tapping "save", or by
     * rating it, which has to save it first -- is the same request every time
     * and the second one does nothing.
     *
     * The React Native app instead remembers the in-flight save per suggestion
     * so two quick taps share one promise. That works while the screen is
     * mounted; this does not need it to be. `OR IGNORE` makes the guarantee the
     * database's rather than the UI's, so it also holds across a rotation, a
     * process death, or two taps landing in genuinely parallel work.
     *
     * Returns true if the row was written, false if it was already there --
     * which is what tells "saved" from "already saved".
     */
    fun insertIfAbsent(
        id: String,
        name: String,
        garmentIds: List<String>,
        occasion: String? = null,
        season: String? = null,
        isSuggested: Boolean = false,
        isPinned: Boolean = false,
        now: String,
    ): Boolean = driver.execute(
        """
        INSERT OR IGNORE INTO outfits (id, name, garment_ids, occasion, season, created_at, is_suggested, is_pinned)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        listOf(
            id, name, jsonArray(garmentIds), occasion, season, now,
            if (isSuggested) 1 else 0, if (isPinned) 1 else 0,
        ),
    ) > 0

    fun setPinned(id: String, isPinned: Boolean) {
        driver.execute(
            "UPDATE outfits SET is_pinned = ? WHERE id = ?",
            listOf(if (isPinned) 1 else 0, id),
        )
    }

    /**
     * Delete an outfit and its rating.
     *
     * The foreign key cascades the rating, but only while
     * `PRAGMA foreign_keys = ON` holds, so it is deleted explicitly too.
     */
    fun delete(id: String) = driver.transaction {
        driver.execute("DELETE FROM outfit_ratings WHERE outfit_id = ?", listOf(id))
        driver.execute("DELETE FROM outfits WHERE id = ?", listOf(id))
    }

    /**
     * Drop a garment from every outfit that references it, so deleting a garment
     * cannot leave outfits pointing at rows that no longer exist.
     *
     * Outfits left with nothing are deleted; ones that still have garments are
     * kept -- their name may read slightly stale, but the outfit is still usable.
     */
    fun removeGarment(garmentId: String) = driver.transaction {
        for (outfit in OutfitQueries(driver).all()) {
            if (!outfit.garmentIds.contains(garmentId)) continue

            val remaining = outfit.garmentIds.filterNot { it == garmentId }
            if (remaining.isEmpty()) {
                delete(outfit.id)
            } else {
                driver.execute(
                    "UPDATE outfits SET garment_ids = ? WHERE id = ?",
                    listOf(jsonArray(remaining), outfit.id),
                )
            }
        }
    }

    /**
     * Rate an outfit, replacing any previous rating and folding the change into
     * the learned pair scores.
     *
     * An outfit carries exactly one rating: re-rating is the user correcting
     * themselves, not a second opinion. The delete also collapses duplicate rows
     * left by earlier versions, which appended on every star tap.
     */
    fun rate(
        ratingId: String,
        outfitId: String,
        rating: Int,
        feedback: String? = null,
        now: String,
    ): RatingRecord = driver.transaction {
        val previous = driver.query(
            "SELECT rating FROM outfit_ratings WHERE outfit_id = ? ORDER BY rated_at DESC",
            listOf(outfitId),
        ).firstOrNull()?.get("rating")?.let { (it as Number).toInt() }

        driver.execute("DELETE FROM outfit_ratings WHERE outfit_id = ?", listOf(outfitId))
        driver.execute(
            "INSERT INTO outfit_ratings (id, outfit_id, rating, feedback, rated_at) VALUES (?, ?, ?, ?, ?)",
            listOf(ratingId, outfitId, rating, feedback, now),
        )

        OutfitQueries(driver).outfit(outfitId)?.let { outfit ->
            applyPairLearning(outfit.garmentIds, rating, previous)
        }

        RatingRecord(ratingId, outfitId, rating, feedback, now)
    }

    private fun applyPairLearning(garmentIds: List<String>, rating: Int, previous: Int?) {
        for ((a, b) in garmentPairs(garmentIds)) {
            val existing = driver.query(
                "SELECT score, wear_count FROM garment_pair_scores WHERE garment_id_a = ? AND garment_id_b = ?",
                listOf(a, b),
            ).firstOrNull()?.let {
                PairScore(
                    score = (it["score"] as Number).toDouble(),
                    wearCount = (it["wear_count"] as Number).toInt(),
                )
            }

            val next = foldRatingIntoPair(existing, rating, previous)

            if (existing == null) {
                driver.execute(
                    "INSERT INTO garment_pair_scores (garment_id_a, garment_id_b, score, wear_count) VALUES (?, ?, ?, ?)",
                    listOf(a, b, next.score, next.wearCount),
                )
            } else {
                driver.execute(
                    "UPDATE garment_pair_scores SET score = ?, wear_count = ? WHERE garment_id_a = ? AND garment_id_b = ?",
                    listOf(next.score, next.wearCount, a, b),
                )
            }
        }
    }
}
