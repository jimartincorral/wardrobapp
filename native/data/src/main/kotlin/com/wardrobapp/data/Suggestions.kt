package com.wardrobapp.data

import com.wardrobapp.domain.GenerateSuggestionsOptions
import com.wardrobapp.domain.Season
import com.wardrobapp.domain.SuggestionContext
import com.wardrobapp.domain.buildSuggestions

/**
 * Loading what the suggestion engine needs, then running it.
 *
 * The algorithm is in :domain and knows nothing about a database or a clock.
 * This is the only part that does -- and it is deliberately the whole of that
 * part, so the engine stays reproducible from its arguments alone.
 */

/**
 * A suggested outfit as a screen needs it.
 *
 * The engine works on the domain's narrow [com.wardrobapp.domain.Garment],
 * which has no idea where a photo lives -- correctly, since none of the scoring
 * depends on that. A screen needs the photos, so the ids come back out of the
 * result and are matched to the records they were built from.
 */
data class SuggestedOutfit(
    val name: String,
    val score: Double,
    val garments: List<GarmentRecord>,
)

class Suggestions(
    private val garments: GarmentQueries,
    private val outfits: OutfitQueries,
) {

    /**
     * Suggest outfits from the available wardrobe.
     *
     * [currentSeason] and [random] are arguments rather than read here, so a run
     * can be reproduced exactly -- which is what the engine's parity tests
     * depend on, and what makes a surprising suggestion investigable.
     */
    fun suggest(
        currentSeason: Season,
        random: () -> Double,
        options: GenerateSuggestionsOptions = GenerateSuggestionsOptions(),
    ): List<SuggestedOutfit> {
        // Explicitly available-only rather than relying on the default's
        // meaning: which garments a suggestion may draw from is this class's
        // decision, and a retired garment must never appear in one.
        val available = garments.allGarments(GarmentQueries.Filters(availableOnly = true))

        // No early return for an empty wardrobe: buildSuggestions already
        // answers with nothing, and a guard here would be a branch no test could
        // tell apart from the engine doing its job.
        val byId = available.associateBy { it.id }

        val scored = buildSuggestions(
            SuggestionContext(
                garments = available.map { it.toDomain() },
                getPairScore = outfits.pairScores(),
                currentSeason = currentSeason,
                random = random,
            ),
            options,
        )

        return scored.map { outfit ->
            SuggestedOutfit(
                name = outfit.name,
                score = outfit.score,
                // Every id came from `available`, so nothing should be missing;
                // dropping a stray rather than throwing means a suggestion is
                // shown short rather than not at all.
                garments = outfit.garments.mapNotNull { byId[it.id] },
            )
        }
    }
}
