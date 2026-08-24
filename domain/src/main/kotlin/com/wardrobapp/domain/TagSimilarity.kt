package com.wardrobapp.domain

/**
 * Jaccard similarity between two tag sets.
 *
 * Returns null when neither side has any tags. That is not the same as 0: 0
 * asserts the tags disagree, null says there is nothing to compare. Callers that
 * blend signals need to tell those apart, otherwise two untagged garments look
 * maximally *dis*similar on the strength of no evidence at all.
 */
fun jaccardSimilarity(tagsA: List<String>, tagsB: List<String>): Double? {
    // Blank entries are not tags; `[""] vs [""]` used to score a perfect 1.
    val setA = tagsA.map { it.lowercase().trim() }.filter { it.isNotEmpty() }.toSet()
    val setB = tagsB.map { it.lowercase().trim() }.filter { it.isNotEmpty() }.toSet()

    if (setA.isEmpty() && setB.isEmpty()) return null

    val intersection = setA.count { it in setB }
    val union = setA.size + setB.size - intersection

    return if (union == 0) null else intersection.toDouble() / union
}
