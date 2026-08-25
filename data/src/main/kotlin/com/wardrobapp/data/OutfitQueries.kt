package com.wardrobapp.data

import com.wardrobapp.domain.PairScoreLookup
import com.wardrobapp.domain.pairKey

/** Reading outfits, their ratings, and the learned pair scores. */
class OutfitQueries(private val driver: SqlDriver) {

    private fun toRecord(row: Map<String, Any?>) = OutfitRecord(
        id = jsString(row["id"] ?: ""),
        name = jsString(row["name"] ?: ""),
        garmentIds = parseStringArray(row["garment_ids"]),
        occasion = row["occasion"] as? String,
        season = row["season"] as? String,
        createdAt = row["created_at"] as? String,
        isSuggested = jsTruthy(row["is_suggested"]),
        isPinned = jsTruthy(row["is_pinned"]),
        isArchived = jsTruthy(row["is_archived"]),
    )

    /**
     * Pinned first, then newest.
     *
     * Archived outfits are left out unless asked for. They exist to keep what a
     * rating taught, not to be worn again, and a list that mixed them in would
     * grow by one every time somebody rated a suggestion they did not want --
     * which is the fastest way to make rating feel like a mistake.
     */
    fun all(includeArchived: Boolean = false): List<OutfitRecord> = driver
        .query(
            if (includeArchived) {
                "SELECT * FROM outfits ORDER BY is_pinned DESC, created_at DESC"
            } else {
                "SELECT * FROM outfits WHERE is_archived = 0 ORDER BY is_pinned DESC, created_at DESC"
            }
        )
        .map(::toRecord)

    /** How many are put away, for a screen that offers to show them. */
    fun archivedCount(): Long = driver
        .query("SELECT COUNT(*) AS total FROM outfits WHERE is_archived = 1")
        .firstOrNull()
        ?.get("total")
        ?.let { (it as Number).toLong() }
        ?: 0L

    fun outfit(id: String): OutfitRecord? = driver
        .query("SELECT * FROM outfits WHERE id = ?", listOf(id))
        .firstOrNull()
        ?.let(::toRecord)

    fun rating(outfitId: String): RatingRecord? = driver.query(
        "SELECT * FROM outfit_ratings WHERE outfit_id = ? ORDER BY rated_at DESC",
        listOf(outfitId),
    ).firstOrNull()?.let { row ->
        RatingRecord(
            id = jsString(row["id"] ?: ""),
            outfitId = jsString(row["outfit_id"] ?: ""),
            rating = (row["rating"] as Number).toInt(),
            feedback = row["feedback"] as? String,
            ratedAt = row["rated_at"] as? String,
        )
    }

    /**
     * Every learned pair score, as the lookup the suggestion engine takes.
     *
     * Loaded in one query rather than queried per pair: the engine asks for a
     * score once per candidate per slot per attempt, which is thousands of
     * lookups for a single set of suggestions.
     */
    fun pairScores(): PairScoreLookup {
        val scores = driver
            .query("SELECT garment_id_a, garment_id_b, score FROM garment_pair_scores")
            .associate { row ->
                pairKey(jsString(row["garment_id_a"]), jsString(row["garment_id_b"])) to
                    (row["score"] as Number).toDouble()
            }

        return PairScoreLookup { a, b -> scores[pairKey(a, b)] ?: 0.0 }
    }
}
