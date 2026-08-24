package com.wardrobapp.domain

/**
 * Duplicate detection.
 *
 * Pure domain logic: the caller supplies what to compare against, so this has no
 * database dependency and can be tested alone.
 */

data class DuplicateMatch(
    val garment: Garment,
    val score: Double,
    val reasons: List<DuplicateReason>,
)

/**
 * Why a garment was reported. Modelled as a type rather than i18n keys so the
 * domain layer does not decide how anything is worded.
 */
enum class DuplicateReason {
    SIMILAR_TAGS,
    SIMILAR_COLOR,
    SAME_SIZE,

    /** Nothing stood out on its own, but the blend cleared the bar. */
    OVERALL_SIMILARITY,
}

/** The subset of a garment duplicate detection actually compares. */
data class DuplicateCandidate(
    val category: String,
    val tags: List<String> = emptyList(),
    val colorPrimary: String,
    val colorPalette: List<String> = emptyList(),
    val size: String? = null,
)

/**
 * Score above which a garment is reported as a likely duplicate.
 *
 * Lower than the original 0.81 because the score is a weighted *average* over
 * the signals that have data, rather than a sum whose maximum depended on how
 * much the user happened to fill in. At 0.81 an exact duplicate with no tags
 * peaked at 0.40 and could never be reported at all.
 */
const val DUPLICATE_THRESHOLD = 0.65

/** One contribution to a duplicate score; a null score means "no data". */
private data class SignalTerm(val weight: Double, val score: Double?)

/**
 * Blend the signals that have something to say, ignoring the ones that do not.
 *
 * Weighting absent data as zero is what made the old score unreachable: with no
 * tags recorded, the tag term contributed nothing but still consumed 0.6 of the
 * available weight, capping an exact duplicate at 0.40 against a 0.81 threshold.
 * Renormalising over the active terms means an unanswered question lowers
 * confidence rather than arguing against a match.
 */
private fun weightedAverage(terms: List<SignalTerm>): Double? {
    val active = terms.filter { it.score != null }
    val totalWeight = active.sumOf { it.weight }
    if (totalWeight == 0.0) return null

    return active.sumOf { it.weight * it.score!! } / totalWeight
}

/**
 * Score a candidate against garments already in the wardrobe.
 *
 * Pure: the caller supplies what to compare against. Returns matches scoring
 * strictly above the threshold, highest first.
 */
fun findDuplicatesAmong(
    newGarment: DuplicateCandidate,
    existing: List<Garment>,
    threshold: Double = DUPLICATE_THRESHOLD,
): List<DuplicateMatch> {
    val matches = mutableListOf<DuplicateMatch>()

    for (garment in existing) {
        // Compare primary against primary. Taking the best match across the whole
        // palette cross-product meant any shared entry pinned this to 1.0 -- and
        // '#000000' is the schema default, so a red garment and a blue one that
        // both happened to list black scored as identical in colour.
        val colorSim = colorSimilarity(newGarment.colorPrimary, garment.primaryColor)

        val bothSizesKnown = !newGarment.size.isNullOrBlank() && !garment.size.isNullOrBlank()
        val sizeMatch = if (bothSizesKnown) {
            if (newGarment.size!!.trim().lowercase() == garment.size!!.trim().lowercase()) 1.0 else 0.0
        } else {
            null
        }

        val tagSim = jaccardSimilarity(newGarment.tags, garment.tags)

        val score = weightedAverage(
            listOf(
                SignalTerm(0.6, tagSim),
                SignalTerm(0.3, colorSim),
                SignalTerm(0.1, sizeMatch),
            )
        )

        if (score == null || score <= threshold) continue

        val reasons = buildList {
            if (tagSim != null && tagSim > 0.5) add(DuplicateReason.SIMILAR_TAGS)
            if (colorSim > 0.7) add(DuplicateReason.SIMILAR_COLOR)
            if (sizeMatch == 1.0) add(DuplicateReason.SAME_SIZE)
            if (isEmpty()) add(DuplicateReason.OVERALL_SIMILARITY)
        }

        matches.add(DuplicateMatch(garment, score, reasons))
    }

    return matches.sortedByDescending { it.score }
}
