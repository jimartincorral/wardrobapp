package com.wardrobapp.data

import com.wardrobapp.domain.GenerateSuggestionsOptions
import com.wardrobapp.domain.OutfitReason
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
    /**
     * Why the engine picked it, most telling first.
     *
     * Carried as the domain's own enum rather than as text: the words belong on
     * the screen, where the reader's language is known.
     */
    val reasons: List<OutfitReason> = emptyList(),
)

class Suggestions(
    private val garments: GarmentQueries,
    private val outfits: OutfitQueries,
) {

    /**
     * Suggest outfits from the available wardrobe.
     *
     * [currentSeason] and [random] are arguments rather than read here, so a run
     * can be reproduced exactly -- which is what the engine's own tests
     * depend on, and what makes a surprising suggestion investigable.
     */
    fun suggest(
        currentSeason: Season,
        random: () -> Double,
        options: GenerateSuggestionsOptions = GenerateSuggestionsOptions(),
        /**
         * A garment every suggestion must be built around, by id.
         *
         * Resolved here rather than passed in as a domain garment, because this
         * is the only layer that can turn an id into one -- and because the seed
         * has to come from the same available set as everything else, so
         * "build an outfit around this" cannot smuggle a retired garment into a
         * suggestion by the back door.
         */
        seedGarmentId: String? = null,
    ): List<SuggestedOutfit> {
        // Explicitly available-only rather than relying on the default's
        // meaning: which garments a suggestion may draw from is this class's
        // decision, and a retired garment must never appear in one.
        val available = garments.allGarments(GarmentQueries.Filters(availableOnly = true))

        // No early return for an empty wardrobe: buildSuggestions already
        // answers with nothing, and a guard here would be a branch no test could
        // tell apart from the engine doing its job.
        val byId = available.associateBy { it.id }

        // Nothing rather than an unseeded batch when the seed cannot be found:
        // somebody asked for outfits around one particular garment, and answering
        // with outfits around anything else is a different question. A retired or
        // deleted garment lands here, which is why it is not an error.
        val seed = seedGarmentId?.let { id -> available.firstOrNull { it.id == id } ?: return emptyList() }

        val scored = buildSuggestions(
            SuggestionContext(
                garments = available.map { it.toDomain() },
                getPairScore = outfits.pairScores(),
                learned = outfits.learnedPreferences(),
                currentSeason = currentSeason,
                random = random,
            ),
            if (seed == null) options else options.copy(seedGarments = listOf(seed.toDomain())),
        )

        return scored.map { outfit ->
            SuggestedOutfit(
                name = outfit.name,
                score = outfit.score,
                reasons = outfit.reasons,
                // Every id came from `available`, so nothing should be missing;
                // dropping a stray rather than throwing means a suggestion is
                // shown short rather than not at all.
                garments = outfit.garments.mapNotNull { byId[it.id] },
            )
        }
    }
}
