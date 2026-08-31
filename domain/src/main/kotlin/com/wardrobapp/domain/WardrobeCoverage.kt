package com.wardrobapp.domain

/**
 * How many outfits a wardrobe can actually finish, and which slot stops it.
 *
 * Pure arithmetic over the same templates the suggestion engine draws from, and
 * deliberately exact rather than sampled: this half of the gaps feature makes
 * claims a reader can check by counting their own clothes, so it must not be an
 * estimate. The expensive, sampled half -- *which* garment to want, and in what
 * colour -- is in WardrobeGaps.kt and builds on the numbers here.
 *
 * The counting rests on two properties of [OUTFIT_TEMPLATES] that are worth
 * stating, because the arithmetic below is wrong if either stops holding:
 *
 *  - No two templates have the same set of slots. So a template's combinations
 *    cannot be another template's, and the totals can be summed without
 *    double-counting. `{top, bottom}` and `{top, bottom, shoes}` are two
 *    different outfits, which is why the shorter template is not simply
 *    contained in the longer one.
 *  - [garmentSlots] answers with at most one slot per garment. So no garment can
 *    fill two slots of the same template, and a template's combination count is
 *    the plain product of its slots' counts rather than a permutation problem.
 *
 * Both are asserted in WardrobeCoverageTest, so a fifteenth template added with
 * a duplicate slot set fails there rather than quietly inflating everybody's
 * numbers.
 */

/** A garment type that could fill a slot: its category, and the type within it. */
data class GarmentType(val category: String, val subcategory: String)

/**
 * Every type in the catalogue, filed under the slot it would fill.
 *
 * Derived by asking [garmentSlots] about a probe of each type rather than
 * tabulated by hand. A second table would drift: `garmentSlots` files a poncho
 * under outerwear and a nightgown under dresses through rules that read a
 * lowercased subcategory, and transcribing those rules here would mean two
 * places to change and one of them forgotten.
 *
 * Types that fill no slot -- socks, briefs, and the rest of the underwear that
 * is deliberately not part of an outfit -- are simply absent.
 */
internal val TYPES_BY_SLOT: Map<OutfitSlot, List<GarmentType>> =
    GARMENT_CATEGORIES
        .flatMap { category -> category.subcategories.map { GarmentType(category.id, it) } }
        .flatMap { type ->
            val probe = Garment(
                id = "",
                category = type.category,
                subcategory = type.subcategory,
                colorPrimary = "#000000",
            )
            garmentSlots(probe).map { slot -> slot to type }
        }
        .groupBy({ it.first }, { it.second })

/**
 * The seasons coverage is reported for.
 *
 * [Season.ALL_SEASON] is excluded on purpose: it is something a garment can be,
 * not a time of year that can arrive, so "coverage in all-season" is not a
 * question. Passing it to [outfitCoverage] anyway is still well defined -- it
 * reads as no seasonal constraint at all -- which is what makes it usable as a
 * "whatever the weather" total.
 */
val REAL_SEASONS: List<Season> = Season.entries.filter { it != Season.ALL_SEASON }

/**
 * How many outfits one more garment in a slot would make possible.
 *
 * The honest reading of a gap, and the reason this is computed rather than
 * guessed at: a wardrobe with eight tops, six bottoms and no shoes does not need
 * a ninth top, and the arithmetic says so without anybody having to decide that
 * shoes matter more than tops.
 */
data class SlotLift(val slot: OutfitSlot, val outfits: Long)

/** What a wardrobe can finish for one kind of day. */
data class OutfitCoverage(
    val occasion: Occasion,
    val season: Season,
    /** Distinct sets of garments the templates can complete. */
    val outfits: Long,
    /**
     * How many available garments are dressed for this kind of day at all.
     *
     * Coverage on its own cannot tell "this wardrobe has a hole in it" from "this
     * wardrobe is not for this" -- both come out as zero outfits. This is the
     * number that separates them: nobody who owns no sportswear whatsoever has a
     * sportswear gap, they have a life that does not involve tracking sportswear
     * in a wardrobe app.
     */
    val eligibleGarments: Int,
    /**
     * Slots nothing eligible fills, that something *could* have filled.
     *
     * Filtered by what the catalogue allows, so a work day is never reported as
     * missing a loungewear set: no type that fills that slot is dressed for work,
     * so the slot being empty is not a gap, it is the category behaving.
     */
    val emptySlots: List<OutfitSlot>,
    /** What one more garment in each slot would add, biggest first. */
    val slotLift: List<SlotLift>,
) {
    /**
     * Nothing to wear for this kind of day at all.
     *
     * Not the same as a thin wardrobe: zero means no template can be completed,
     * which is the strongest thing coverage can say and the only claim that needs
     * no threshold to be worth showing.
     */
    val isBare: Boolean get() = outfits == 0L
}

/**
 * Whether any garment type could fill [slot] for [occasion].
 *
 * Asked of the catalogue rather than of the wardrobe: this decides whether an
 * empty slot is worth mentioning, so it has to be a fact about clothes and not
 * about the clothes somebody happens to own.
 */
internal fun slotCanSuit(slot: OutfitSlot, occasion: Occasion): Boolean =
    TYPES_BY_SLOT[slot]?.any { type ->
        occasionsFor(type.category, listOf(type.subcategory)).contains(occasion)
    } ?: false

/**
 * Garment types that could fill [slot] and are dressed for [occasion].
 *
 * The candidate set the sampled half of the feature draws from, kept here
 * because it is the same question [slotCanSuit] answers, only asked for the list
 * instead of for whether the list is empty.
 */
internal fun typesFor(slot: OutfitSlot, occasion: Occasion): List<GarmentType> =
    TYPES_BY_SLOT[slot]?.filter { type ->
        occasionsFor(type.category, listOf(type.subcategory)).contains(occasion)
    } ?: emptyList()

/**
 * Garments eligible for a kind of day, counted by the slot they fill.
 *
 * Retired garments are dropped here rather than left to the caller. Coverage is a
 * claim about what can be worn today, and a wardrobe reported as having three
 * pairs of shoes when two of them were thrown out is the exact failure this
 * feature exists to catch -- so it must not be the failure the feature itself
 * makes. The suggestion engine takes the same view from the other side, filtering
 * in :data before the engine ever sees a garment.
 */
internal fun eligibleCounts(
    garments: List<Garment>,
    occasion: Occasion,
    season: Season,
): Map<OutfitSlot, Int> {
    val counts = mutableMapOf<OutfitSlot, Int>()

    for (garment in garments) {
        if (!garment.isAvailable) continue
        if (!garment.occasions().contains(occasion)) continue
        // The engine's own definition of "suits this season", asked with the
        // season as an explicit selection so an opposite-season tag rules the
        // garment out. Untagged garments still pass, which is what keeps this
        // usable for somebody who has never tagged anything.
        if (!matchesSeason(garment, season, listOf(season))) continue

        for (slot in garmentSlots(garment)) {
            counts[slot] = (counts[slot] ?: 0) + 1
        }
    }

    return counts
}

/**
 * Combinations a template can complete from the given counts.
 *
 * Zero as soon as any slot is empty, which is the whole point: a template is
 * either fillable or it is not, and a wardrobe of forty tops completes none of
 * the six templates that call for shoes.
 */
private fun combinations(template: List<OutfitSlot>, counts: Map<OutfitSlot, Int>): Long {
    var product = 1L
    for (slot in template) {
        val available = counts[slot] ?: 0
        if (available == 0) return 0L
        product *= available
    }
    return product
}

/**
 * What a wardrobe can finish for one kind of day, and what is stopping it.
 *
 * Exact and closed-form: no outfit is enumerated and none needs to be. The
 * templates are known, the counts per slot are one pass over the wardrobe, and a
 * template's combinations are the product of its slots.
 *
 * The lift figure is the same product with one slot left out, summed over the
 * templates that call for that slot -- which is precisely how many outfits one
 * more garment there would complete. A template still missing a *different* slot
 * contributes nothing to it, correctly: one pair of shoes does not finish an
 * outfit that also has no bottoms.
 */
fun outfitCoverage(
    garments: List<Garment>,
    occasion: Occasion,
    season: Season,
): OutfitCoverage {
    val counts = eligibleCounts(garments, occasion, season)

    var total = 0L
    val lift = mutableMapOf<OutfitSlot, Long>()

    for (template in OUTFIT_TEMPLATES) {
        total += combinations(template, counts)

        for (slot in template) {
            // A slot no garment type could suit this occasion in is not a gap,
            // and promising outfits from it is a lie the total will not honour.
            // The solo templates are where this bit: `[ACTIVEWEAR_SETS]` needs
            // nothing else, so its lift is an unconditional 1 -- and a casual day
            // that added the track suit it asked for would gain no outfit at all,
            // because a track suit is dressed for sport and lounge and nothing is
            // eligible that is not. Filtered here rather than at the end so the
            // figure is never computed for a slot that cannot deliver it.
            if (!slotCanSuit(slot, occasion)) continue

            val withoutSlot = combinations(template.filter { it != slot }, counts)
            if (withoutSlot > 0) lift[slot] = (lift[slot] ?: 0L) + withoutSlot
        }
    }

    val empty = OutfitSlot.entries.filter { slot ->
        (counts[slot] ?: 0) == 0 && slotCanSuit(slot, occasion)
    }

    return OutfitCoverage(
        occasion = occasion,
        season = season,
        outfits = total,
        // The sum of the per-slot counts is the garment count, because
        // `garmentSlots` files a garment under at most one slot -- the same
        // property the products above depend on, asserted in the test.
        eligibleGarments = counts.values.sum(),
        emptySlots = empty,
        // Ties broken by slot declaration order rather than left to the map's
        // iteration order, so the same wardrobe reports the same gap twice
        // running -- a list whose order came from a HashMap would reorder itself
        // between screens for no reason a reader could see.
        //
        // Ties are common and they are real: with tops and bottoms and nothing
        // else, one jacket and one pair of shoes each complete exactly the same
        // number of outfits, and counting cannot tell them apart because there is
        // nothing to tell. The engine's own view that shoes matter more lives in
        // `templateWeight`, and is deliberately not consulted here -- it triples
        // the odds of drawing a shoe template, so a lift measured through it
        // would answer "shoes" for every wardrobe on earth and stop being a
        // measurement. Which of two tied slots to actually want is settled by the
        // sampled half of the feature, on whether the outfits would be any good.
        slotLift = lift.entries
            .sortedWith(compareByDescending<Map.Entry<OutfitSlot, Long>> { it.value }
                .thenBy { it.key.ordinal })
            .map { SlotLift(it.key, it.value) },
    )
}

/**
 * Coverage for every kind of day, worst first.
 *
 * The whole grid rather than a filtered one: which cells are worth showing is a
 * decision about screen space and about what its reader cares about, and this
 * module is not entitled to make it. Sorted, though, because "worst first" is
 * not a matter of taste.
 */
fun coverageGrid(
    garments: List<Garment>,
    occasions: List<Occasion> = Occasion.entries,
    seasons: List<Season> = REAL_SEASONS,
): List<OutfitCoverage> =
    occasions
        .flatMap { occasion -> seasons.map { season -> outfitCoverage(garments, occasion, season) } }
        .sortedWith(
            compareBy<OutfitCoverage> { it.outfits }
                .thenBy { it.occasion.ordinal }
                .thenBy { it.season.ordinal }
        )
