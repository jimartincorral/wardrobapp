package com.wardrobapp.domain

/**
 * What a rating teaches, beyond the pair of garments it was given to.
 *
 * A rating carries more than "these two go together". It also says something
 * about each garment on its own -- a garment that keeps appearing in outfits you
 * rate badly is probably one you do not like, whatever it is paired with -- and
 * something about the *kind* of colour pairing the outfit was built on, which is
 * how a hardcoded aesthetic becomes somebody's own.
 *
 * All three signals share one arithmetic, deliberately: an exponential moving
 * average over ratings normalised to -1..+1, with a count of how much evidence is
 * behind it. Three learning rules would be three things to get wrong, and the
 * one that already existed for pairs was the one worth keeping.
 *
 * Nothing here decides how much to trust what it has learned. That is
 * [learningConfidence], and it is the difference between a suggestion engine that
 * improves with use and one that lurches after a single rating.
 */

/**
 * How many ratings before a learned value is trusted as much as the default it
 * replaces.
 *
 * Eight is a guess, but a considered one: a learned value with two ratings behind
 * it is noise, and one with fifty is not, so the crossover belongs between them
 * and nearer the low end -- a preference nobody can see taking effect is a
 * preference nobody believes in.
 */
const val LEARNING_CONFIDENCE_HALFWAY = 8

/**
 * How far a learned value should be trusted, from 0 (no evidence) to just under 1.
 *
 * Never reaches 1, so a default is never entirely discarded: the hardcoded
 * aesthetics are a reasonable prior, and a preference learned from thirty ratings
 * should outweigh them rather than erase them.
 */
fun learningConfidence(count: Int): Double =
    if (count <= 0) 0.0 else count.toDouble() / (count + LEARNING_CONFIDENCE_HALFWAY)

/** Something learned from ratings, and how many are behind it. */
data class LearnedScore(
    /** -1 (rated badly) to +1 (rated well), 0 for indifferent. */
    val score: Double,
    /** How many ratings have contributed. Corrections do not count twice. */
    val count: Int,
) {
    /**
     * The score, damped by how little is known.
     *
     * What a caller should actually add to a judgement: a single five-star rating
     * moves a score most of the way to +1, and acting on that at full strength is
     * how one tap rearranges a wardrobe.
     */
    val trusted: Double get() = score * learningConfidence(count)
}

/**
 * Fold a rating into a learned score.
 *
 * `previous` is the rating this one replaces, when somebody is correcting
 * themselves rather than rating something new. The moving-average step
 *
 *     next = old * (1 - lr) + r * lr
 *
 * inverts exactly, so a correction moves the score to where it would have been
 * had the rating been right the first time, instead of training on both values.
 * The count is not incremented for a correction: one opinion was given, not two.
 */
fun foldRatingIntoScore(
    existing: LearnedScore?,
    rating: Int,
    previous: Int? = null,
): LearnedScore {
    val normalized = normalizeRating(rating)

    if (existing == null) {
        // A first rating starts from an implicit zero, so the step reduces to the
        // rating's own share.
        return LearnedScore(score = normalized * PAIR_LEARNING_RATE, count = 1)
    }

    val base = if (previous == null) {
        existing.score
    } else {
        (existing.score - normalizeRating(previous) * PAIR_LEARNING_RATE) / (1 - PAIR_LEARNING_RATE)
    }

    return LearnedScore(
        score = base * (1 - PAIR_LEARNING_RATE) + normalized * PAIR_LEARNING_RATE,
        count = if (previous == null) existing.count + 1 else existing.count,
    )
}

/**
 * What the app has learned, as lookups the engine can ask.
 *
 * Lambdas rather than maps so the caller decides where the answers come from --
 * a database on a phone, a literal in a test -- and defaults that know nothing,
 * so an engine given none of this behaves exactly as it did before any of it
 * existed.
 */
data class LearnedPreferences(
    /** How a garment itself has been rated, whatever it was worn with. */
    val garment: (String) -> LearnedScore? = { null },
    /** How outfits built on a kind of colour pairing have been rated. */
    val colorRelationship: (ColorRelationship) -> LearnedScore? = { null },
) {
    companion object {
        /** Knows nothing, and so changes nothing. */
        val NONE = LearnedPreferences()
    }
}
