package com.wardrobapp.data

import com.wardrobapp.domain.DUPLICATE_THRESHOLD
import com.wardrobapp.domain.DuplicateCandidate
import com.wardrobapp.domain.DuplicateReason
import com.wardrobapp.domain.findDuplicatesAmong

/**
 * Finding the garments a new one might already be.
 *
 * The scoring is in :domain and compares nothing but what it is handed. This
 * loads the candidates, which is the only part that touches a database -- and
 * which garments are even considered is the decision that lives here.
 */

/** A likely duplicate, with the photos a warning needs to show. */
data class DuplicateGarment(
    val garment: GarmentRecord,
    val score: Double,
    val reasons: List<DuplicateReason>,
)

class Duplicates(private val garments: GarmentQueries) {

    /**
     * Score a candidate against the wardrobe, highest first.
     *
     * Only the same category, and only garments still in use. A retired garment
     * is not something you already own, and warning about one would tell someone
     * to go and look for a garment they deliberately put away.
     */
    fun matching(
        candidate: DuplicateCandidate,
        threshold: Double = DUPLICATE_THRESHOLD,
    ): List<DuplicateGarment> {
        val existing = garments.allGarments(
            GarmentQueries.Filters(category = candidate.category, availableOnly = true)
        )
        val byId = existing.associateBy { it.id }

        return findDuplicatesAmong(candidate, existing.map { it.toDomain() }, threshold)
            .mapNotNull { match ->
                // Every id came from `existing`; a missing one would be a bug
                // rather than a state, and skipping it beats crashing a save.
                byId[match.garment.id]?.let {
                    DuplicateGarment(garment = it, score = match.score, reasons = match.reasons)
                }
            }
    }
}
