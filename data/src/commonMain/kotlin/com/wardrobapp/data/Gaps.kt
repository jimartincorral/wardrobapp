package com.wardrobapp.data

import com.wardrobapp.domain.GapContext
import com.wardrobapp.domain.GapOptions
import com.wardrobapp.domain.OutfitCoverage
import com.wardrobapp.domain.PHANTOM_GARMENT_ID
import com.wardrobapp.domain.Season
import com.wardrobapp.domain.WardrobeGap
import com.wardrobapp.domain.analyzeGaps
import com.wardrobapp.domain.coverageGrid
import kotlin.random.Random

/**
 * Loading what the gap analysis needs, then running it.
 *
 * The same shape as [Suggestions], and for the same reason: the algorithm is in
 * :domain and knows nothing about a database or a clock, so this is the whole of
 * the part that does.
 *
 * Two differences from [Suggestions] are deliberate and worth stating, because
 * both look like mistakes:
 *
 *  - Retired garments are loaded rather than filtered out. A garment that was
 *    thrown away and never replaced is the strongest gap the analysis can find,
 *    and it cannot find one in a list it has already been removed from. The
 *    analysis filters for itself everywhere that matters.
 *  - The randomness is seeded, and seeded the same way every time. A suggestion
 *    that came back different on a second tap is variety; advice about what your
 *    wardrobe is missing that came back different on a second tap is a bug. See
 *    [GAP_SEED].
 */

/**
 * The seed every gap run uses.
 *
 * A constant rather than a clock, so the same wardrobe is always told the same
 * thing. This is the opposite of what the suggestions screen wants -- there,
 * drawing the same three outfits twice reads as a broken button -- and the
 * difference is that a suggestion is an offer while this is an answer. Somebody
 * who closes the section and opens it again is checking what it said, not asking
 * again.
 *
 * The value itself means nothing; it only has to stay the same.
 */
const val GAP_SEED: Long = 20260831L

/**
 * One outfit a gap would complete, with the photos to draw it.
 *
 * The garment that does not exist yet comes through as null rather than being
 * dropped, because where it sits is the point: the screen draws that position as
 * an empty frame among real photographs, which is the whole of the case the card
 * makes. Dropping it would leave an outfit that looks like one the reader could
 * already put on.
 */
data class GapOutfit(
    val name: String,
    val garments: List<GarmentRecord?>,
)

/** A gap as a screen needs it: the domain's answer, plus the photographs. */
data class GapWithPhotos(
    val gap: WardrobeGap,
    val examples: List<GapOutfit>,
    /**
     * The retired garment this would stand in for, resolved to its record.
     *
     * Null unless the evidence is [com.wardrobapp.domain.GapEvidence.RETIRED_UNREPLACED].
     */
    val replaces: GarmentRecord?,
)

class Gaps(
    private val garments: GarmentQueries,
    private val outfits: OutfitQueries,
) {

    /**
     * What the wardrobe is missing, most worth saying first.
     *
     * [currentSeason] is an argument rather than read here, exactly as it is for
     * [Suggestions.suggest]: this layer is allowed a clock and the analysis is
     * not.
     */
    fun analyze(
        currentSeason: Season,
        options: GapOptions = GapOptions(),
        seed: Long = GAP_SEED,
    ): List<GapWithPhotos> {
        val everything = wardrobe()
        val byId = everything.associateBy { it.id }

        val found = analyzeGaps(
            GapContext(
                garments = everything.map { it.toDomain() },
                getPairScore = outfits.pairScores(),
                learned = outfits.learnedPreferences(),
                currentSeason = currentSeason,
                // A fresh generator per candidate, all starting from the same
                // seed. The factory is not a formality: sharing one generator
                // would make a candidate's score depend on how many were measured
                // before it, so reordering the catalogue would change the advice.
                newRandom = {
                    val random = Random(seed)
                    // Named rather than returned as a bare lambda: written that
                    // way the compiler reads it as an argument to `Random(seed)`.
                    val draw: () -> Double = { random.nextDouble() }
                    draw
                },
            ),
            options,
        )

        return found.map { gap ->
            GapWithPhotos(
                gap = gap,
                examples = gap.examples.map { outfit ->
                    GapOutfit(
                        name = outfit.name,
                        // The phantom keeps its place as a null. Everything else
                        // came out of `everything`, so a miss should not happen --
                        // and a stray dropped rather than thrown means one short
                        // example rather than no card at all, which is how
                        // [Suggestions] handles the same impossibility.
                        garments = outfit.garments.map {
                            if (it.id == PHANTOM_GARMENT_ID) null else byId[it.id]
                        },
                    )
                },
                replaces = gap.replaces?.let { byId[it.id] },
            )
        }
    }

    /**
     * What the wardrobe can finish, for every kind of day and every season.
     *
     * Exact, cheap, and independent of [analyze] -- no sampling and no engine, so
     * a screen can show this without paying for the recommendation. Offered whole
     * rather than filtered: which cells are worth a reader's attention is the
     * screen's decision.
     */
    fun coverage(): List<OutfitCoverage> = coverageGrid(wardrobe().map { it.toDomain() })

    /**
     * Every garment, with the retired ones most recently retired first.
     *
     * The order is load-bearing for exactly one thing: when a slot is empty and
     * something used to fill it, the analysis names the first retired garment it
     * finds. A domain [com.wardrobapp.domain.Garment] carries no dates -- and does
     * not need to -- so "the one you got rid of most recently" can only be decided
     * here, by the layer that can see `unavailable_date`.
     *
     * Nulls last, and among nulls the read order stands: a garment retired before
     * the column existed has no date to sort on, and it should not outrank one
     * that does.
     */
    private fun wardrobe(): List<GarmentRecord> {
        val all = garments.allGarments(GarmentQueries.Filters(availableOnly = false))
        val (retired, available) = all.partition { !it.isAvailable }

        return available + retired.sortedWith(
            compareByDescending(nullsFirst<String>()) { it.unavailableDate }
        )
    }
}
