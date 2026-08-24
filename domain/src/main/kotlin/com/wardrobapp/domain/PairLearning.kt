package com.wardrobapp.domain

/**
 * How a rating becomes a learned affinity between two garments.
 *
 * Pure, and deliberately separated from the storage that applies it: this is the
 * part with an actual argument in it. Everything else about rating an outfit is
 * bookkeeping.
 */

/** Weight given to the newest rating in the running average. */
const val PAIR_LEARNING_RATE = 0.3

/** Map a 1-5 star rating onto -1.0 .. +1.0, so 3 stars is neutral. */
fun normalizeRating(rating: Int): Double = (rating - 3) / 2.0

/** A pair's learned state. */
data class PairScore(
    val score: Double,
    val wearCount: Int,
)

/**
 * Fold a rating into a pair's score.
 *
 * `previous` is the rating this one replaces, if the user is correcting
 * themselves rather than rating something new. In that case the earlier
 * rating's contribution is undone before the new one is applied -- the
 * exponential-moving-average step
 *
 *     next = old * (1 - lr) + r * lr
 *
 * inverts exactly, so correcting a rating moves the score to where it would
 * have been had the user rated correctly the first time, instead of training on
 * both values. `wearCount` counts how often a pair has actually been worn
 * together, so a correction must not increment it.
 *
 * @param existing the pair's current state, or null if this is its first rating
 */
fun foldRatingIntoPair(
    existing: PairScore?,
    rating: Int,
    previous: Int? = null,
): PairScore {
    val normalized = normalizeRating(rating)

    if (existing == null) {
        // A first rating starts from an implicit score of zero, so the EMA step
        // reduces to the rating's own share.
        return PairScore(score = normalized * PAIR_LEARNING_RATE, wearCount = 1)
    }

    val base = if (previous == null) {
        existing.score
    } else {
        // The inverse of the EMA step, undoing the rating being replaced.
        (existing.score - normalizeRating(previous) * PAIR_LEARNING_RATE) / (1 - PAIR_LEARNING_RATE)
    }

    return PairScore(
        score = base * (1 - PAIR_LEARNING_RATE) + normalized * PAIR_LEARNING_RATE,
        wearCount = if (previous == null) existing.wearCount + 1 else existing.wearCount,
    )
}

/**
 * Every unordered pair in an outfit, each as a stable key-ordered pair.
 *
 * Ordering the two ids means a pair is stored once however the outfit lists it,
 * which is what makes [pairKey] and the lookup agree.
 */
fun garmentPairs(garmentIds: List<String>): List<Pair<String, String>> {
    val pairs = mutableListOf<Pair<String, String>>()
    for (i in garmentIds.indices) {
        for (j in i + 1 until garmentIds.size) {
            val a = garmentIds[i]
            val b = garmentIds[j]
            pairs.add(if (a <= b) a to b else b to a)
        }
    }
    return pairs
}
