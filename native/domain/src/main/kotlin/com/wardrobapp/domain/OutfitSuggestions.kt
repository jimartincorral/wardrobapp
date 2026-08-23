package com.wardrobapp.domain

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * Outfit suggestion algorithm.
 *
 * Pure domain logic: no database, no filesystem, no clock, no platform. Every
 * input arrives through [SuggestionContext], so a run is reproducible -- which is
 * what made this portable, and what lets the port be checked against the
 * TypeScript draw for draw.
 */

enum class OutfitSlot {
    TOPS,
    BOTTOMS,
    DRESSES,
    OUTERWEAR,
    SHOES,
    ACCESSORIES,
    ACTIVEWEAR_SETS,
    LOUNGEWEAR_SETS,
}

/** Which category slots make a valid outfit. Order is part of the behaviour. */
private val OUTFIT_TEMPLATES: List<List<OutfitSlot>> = listOf(
    listOf(OutfitSlot.TOPS, OutfitSlot.BOTTOMS),
    listOf(OutfitSlot.TOPS, OutfitSlot.BOTTOMS, OutfitSlot.OUTERWEAR),
    listOf(OutfitSlot.TOPS, OutfitSlot.BOTTOMS, OutfitSlot.ACCESSORIES),
    listOf(OutfitSlot.TOPS, OutfitSlot.BOTTOMS, OutfitSlot.SHOES),
    listOf(OutfitSlot.TOPS, OutfitSlot.BOTTOMS, OutfitSlot.SHOES, OutfitSlot.OUTERWEAR),
    listOf(OutfitSlot.DRESSES),
    listOf(OutfitSlot.DRESSES, OutfitSlot.OUTERWEAR),
    listOf(OutfitSlot.DRESSES, OutfitSlot.SHOES),
    listOf(OutfitSlot.DRESSES, OutfitSlot.SHOES, OutfitSlot.OUTERWEAR),
    listOf(OutfitSlot.TOPS, OutfitSlot.BOTTOMS, OutfitSlot.SHOES, OutfitSlot.ACCESSORIES),
    listOf(OutfitSlot.ACTIVEWEAR_SETS),
    listOf(OutfitSlot.ACTIVEWEAR_SETS, OutfitSlot.OUTERWEAR),
    listOf(OutfitSlot.ACTIVEWEAR_SETS, OutfitSlot.SHOES),
    listOf(OutfitSlot.LOUNGEWEAR_SETS),
    listOf(OutfitSlot.LOUNGEWEAR_SETS, OutfitSlot.OUTERWEAR),
)

data class ScoredOutfit(
    val garments: List<Garment>,
    val score: Double,
    val name: String,
)

/** Looks up the learned score for a garment pair, in either order. */
fun interface PairScoreLookup {
    fun score(idA: String, idB: String): Double
}

/** Stable key for a garment pair, independent of the order given. */
fun pairKey(idA: String, idB: String): String =
    if (idA < idB) "$idA|$idB" else "$idB|$idA"

/** Squash the open-ended ranking score into a stable 0..1 display value. */
private fun normalizeOutfitScore(rawScore: Double): Double = 1 / (1 + exp(-rawScore / 2))

private fun garmentSlots(garment: Garment): List<OutfitSlot> {
    val sub = (garment.subcategory ?: "").lowercase()

    return when (garment.category) {
        "tops" -> listOf(OutfitSlot.TOPS)
        "bottoms" -> listOf(OutfitSlot.BOTTOMS)
        "dresses" -> listOf(OutfitSlot.DRESSES)
        "outerwear" -> listOf(OutfitSlot.OUTERWEAR)
        "shoes" -> listOf(OutfitSlot.SHOES)
        "accessories" -> listOf(OutfitSlot.ACCESSORIES)
        // Blazers/vests/ponchos layer over tops like light outerwear.
        "midlayer" -> listOf(OutfitSlot.OUTERWEAR)
        "activewear" -> when {
            sub.contains("track suit") -> listOf(OutfitSlot.ACTIVEWEAR_SETS)
            sub.contains("short") || sub.contains("pants") -> listOf(OutfitSlot.BOTTOMS)
            else -> listOf(OutfitSlot.TOPS)
        }
        "underwear" -> when {
            sub.contains("bodysuit") || sub.contains("thermal") -> listOf(OutfitSlot.TOPS)
            sub.contains("tights") -> listOf(OutfitSlot.BOTTOMS)
            else -> emptyList()
        }
        "loungewear" -> when {
            sub.contains("set") -> listOf(OutfitSlot.LOUNGEWEAR_SETS)
            sub.contains("nightgown") -> listOf(OutfitSlot.DRESSES)
            sub.contains("robe") -> listOf(OutfitSlot.OUTERWEAR)
            sub.contains("bottom") -> listOf(OutfitSlot.BOTTOMS)
            else -> listOf(OutfitSlot.TOPS)
        }
        else -> emptyList()
    }
}

private fun normalizedTags(garment: Garment): List<String> = garment.tags.map { it.lowercase() }

private fun isHotCompatibleOuterwear(garment: Garment): Boolean {
    if (garment.category != "outerwear") return true
    val tags = normalizedTags(garment)
    val sub = (garment.subcategory ?: "").lowercase()
    if (tags.contains("lightweight") || tags.contains("summer")) return true
    return sub in listOf("vest", "windbreaker", "cardigan", "blazer")
}

data class GenerateSuggestionsOptions(
    val count: Int = 3,
    val preferences: SuggestionPreferences? = null,
    val seedGarments: List<Garment> = emptyList(),
)

/** Whether a garment's tags match the season in play. */
private fun matchesSeason(
    garment: Garment,
    currentSeason: Season,
    seasons: List<Season>?,
): Boolean {
    val tags = normalizedTags(garment)
    val selectedSeasons = (seasons ?: emptyList()).filter { it != Season.ALL_SEASON }
    val activeSeasons = selectedSeasons.ifEmpty { listOf(currentSeason) }

    if (tags.contains(Season.ALL_SEASON.tag)) return true
    if (activeSeasons.any { tags.contains(it.tag) }) return true

    if (activeSeasons.size == 1) {
        val opposite = activeSeasons[0].opposite
        if (opposite != null && tags.contains(opposite.tag)) return false
    }

    return true // No season tag = assume ok
}

private fun occasionFit(garment: Garment, preferences: SuggestionPreferences?): Double {
    val occasion = preferences?.occasion ?: return 0.0
    // Derived from the garment's type -- occasion is not a stored tag.
    return if (garment.occasions().contains(occasion)) 1.0 else 0.0
}

/**
 * Whether a garment suits the season in play: +1 for a fit, -1 against an
 * explicit selection it contradicts, 0 when there is nothing to say.
 */
private fun seasonFit(
    garment: Garment,
    currentSeason: Season,
    preferences: SuggestionPreferences?,
): Double {
    val hasSelection = !preferences?.seasons.isNullOrEmpty()
    val fits = matchesSeason(garment, currentSeason, preferences?.seasons)
    if (hasSelection) return if (fits) 1.0 else -1.0
    // With no selection, reward a seasonal fit but do not punish a silent garment.
    return if (fits) 1.0 else 0.0
}

/**
 * Per-garment steer used while *choosing* garments, as opposed to scoring a
 * finished outfit. Season and occasion both belong here.
 */
private fun contextScore(
    garment: Garment,
    currentSeason: Season,
    preferences: SuggestionPreferences?,
): Double = seasonFit(garment, currentSeason, preferences) + occasionFit(garment, preferences)

/** Score a candidate outfit. */
private fun scoreOutfit(
    garments: List<Garment>,
    getPairScore: PairScoreLookup,
    currentSeason: Season,
    preferences: SuggestionPreferences?,
): Double {
    var score = 0.0

    // Pair scores from learning
    var pairTotal = 0.0
    var pairCount = 0
    for (i in garments.indices) {
        for (j in i + 1 until garments.size) {
            pairTotal += getPairScore.score(garments[i].id, garments[j].id)
            pairCount++
        }
    }
    if (pairCount > 0) score += (pairTotal / pairCount) * 3 // Weight learned preferences heavily

    // Season and occasion, each counted exactly once. Season used to be added
    // here at weight 1.0 and again inside contextScore at weight 1.2, giving it
    // an effective weight of 2.2 -- more than colour harmony, and more than
    // intended.
    val seasonTotal = garments.sumOf { seasonFit(it, currentSeason, preferences) }
    score += (seasonTotal / garments.size) * 1.0

    val occasionTotal = garments.sumOf { occasionFit(it, preferences) }
    score += (occasionTotal / garments.size) * 1.2

    // Colour harmony
    var harmonyTotal = 0.0
    var harmonyCount = 0
    for (i in garments.indices) {
        for (j in i + 1 until garments.size) {
            harmonyTotal += colorHarmonyScore(garments[i].primaryColor, garments[j].primaryColor)
            harmonyCount++
        }
    }
    if (harmonyCount > 0) score += (harmonyTotal / harmonyCount) * 1.5

    return score
}

/** Scores within this of the best count as tied rather than beaten. */
private const val SCORE_TIE_EPSILON = 1e-9

/**
 * Pick whichever candidate fits the outfit so far best, choosing at random
 * between equals.
 *
 * Cold, everything ties: no pair has a learned score, and harmony is the same
 * bucket for most palette pairs. The comparison used to be a strict `>` against
 * negative infinity, which always kept the *first* candidate -- and candidates
 * arrive newest-first, so one garment took 722 of 900 slots in a 20-garment
 * wardrobe.
 */
private fun pickBestFit(
    available: List<Garment>,
    selected: List<Garment>,
    getPairScore: PairScoreLookup,
    currentSeason: Season,
    random: () -> Double,
    preferences: SuggestionPreferences?,
): Garment {
    var bestScore = Double.NEGATIVE_INFINITY
    var tied = mutableListOf<Garment>()

    for (candidate in available) {
        val pairScoreSum = selected.sumOf { getPairScore.score(candidate.id, it.id) }
        val harmony = selected.sumOf { colorHarmonyScore(candidate.primaryColor, it.primaryColor) }
        val total = pairScoreSum + harmony + contextScore(candidate, currentSeason, preferences) * 1.5

        if (total > bestScore + SCORE_TIE_EPSILON) {
            bestScore = total
            tied = mutableListOf(candidate)
        } else if (abs(total - bestScore) <= SCORE_TIE_EPSILON) {
            tied.add(candidate)
        }
    }

    return tied[(random() * tied.size).toInt().coerceIn(0, tied.size - 1)]
}

/**
 * Pick at random, in proportion to how well each candidate fits the context.
 *
 * This is the exploration half of epsilon-greedy, and it has to be able to reach
 * everything. It used to sort by weight and sample from the top 60%, so with no
 * preferences set every weight was equal, the sort was a no-op, and the oldest
 * 40% of every slot could never be picked at all -- never suggested, so never
 * rated, so never able to earn a score that would make them reachable. A
 * roulette-wheel draw keeps the bias towards good fits without excluding anyone.
 */
private fun pickWeightedAtRandom(
    available: List<Garment>,
    currentSeason: Season,
    random: () -> Double,
    preferences: SuggestionPreferences?,
): Garment {
    val weights = available.map {
        maxOf(0.1, 1 + contextScore(it, currentSeason, preferences))
    }
    val total = weights.sum()

    var ticket = random() * total
    for (i in available.indices) {
        ticket -= weights[i]
        if (ticket <= 0) return available[i]
    }
    // Only reachable through floating-point drift at the very end of the wheel.
    return available.last()
}

/**
 * Everything the engine needs from outside itself.
 *
 * Passing these in rather than fetching them keeps [buildSuggestions] pure: the
 * same context and options always produce the same outfits, which is what makes
 * the behaviour testable and the algorithm portable.
 */
data class SuggestionContext(
    /** Garments the suggestion may draw from. */
    val garments: List<Garment>,
    /** Learned affinity for a garment pair, in either order. */
    val getPairScore: PairScoreLookup,
    /** Season assumed when the user has not selected one. */
    val currentSeason: Season,
    /** Source of randomness, injected so a run can be reproduced. */
    val random: () -> Double,
)

/**
 * Generate outfit suggestions from an explicit context.
 *
 * Uses epsilon-greedy: 80% best-scoring picks, 20% random for variety.
 */
fun buildSuggestions(
    context: SuggestionContext,
    options: GenerateSuggestionsOptions = GenerateSuggestionsOptions(),
): List<ScoredOutfit> {
    val count = options.count
    val preferences = options.preferences
    val seedGarments = options.seedGarments
    val garments = context.garments
    val getPairScore = context.getPairScore
    val currentSeason = context.currentSeason
    val random = context.random

    if (garments.isEmpty()) return emptyList()

    val seedSlots = seedGarments.flatMap { garmentSlots(it) }.toSet()

    // Group by outfit slot so categories that behave like tops/bottoms are
    // supported. Insertion order within each slot is preserved: it is what the
    // tie-breaking in pickBestFit sees.
    val bySlot = linkedMapOf<OutfitSlot, MutableList<Garment>>()
    for (g in garments) {
        for (slot in garmentSlots(g)) {
            bySlot.getOrPut(slot) { mutableListOf() }.add(g)
        }
    }

    // Keep heavy outerwear out of summer outfits. This used to key off a "hot"
    // weather filter; with weather gone it keys off an explicit summer
    // selection, which is the same intent in the vocabulary that remains.
    val summerOnly = preferences?.seasons?.size == 1 && preferences.seasons[0] == Season.SUMMER
    if (summerOnly && bySlot[OutfitSlot.OUTERWEAR] != null) {
        bySlot[OutfitSlot.OUTERWEAR] =
            bySlot[OutfitSlot.OUTERWEAR]!!.filter { isHotCompatibleOuterwear(it) }.toMutableList()
    }

    // Viable templates are the ones we have garments for. With seed garments,
    // narrow further to templates that include every seeded slot.
    var viableTemplates = OUTFIT_TEMPLATES.filter { template ->
        template.all { slot -> !bySlot[slot].isNullOrEmpty() }
    }

    if (seedSlots.isNotEmpty()) {
        viableTemplates = viableTemplates.filter { template ->
            seedSlots.all { slot -> template.contains(slot) }
        }
    }

    if (viableTemplates.isEmpty()) return emptyList()

    val candidates = mutableListOf<ScoredOutfit>()
    val attempts = min(count * 5, 20)

    for (i in 0 until attempts) {
        val template = viableTemplates[
            (random() * viableTemplates.size).toInt().coerceIn(0, viableTemplates.size - 1)
        ]

        val selected = seedGarments.toMutableList()
        val usedGarmentIds = seedGarments.map { it.id }.toMutableSet()

        for (slot in template) {
            // A seeded garment already fills its slot. Without this the loop
            // filled it again -- seeding a top produced outfits containing two
            // tops, because usedGarmentIds blocks repeating a garment but not
            // repeating a slot.
            if (seedSlots.contains(slot)) continue

            val available = (bySlot[slot] ?: emptyList<Garment>())
                .filter { !usedGarmentIds.contains(it.id) }
            if (available.isEmpty()) continue

            // The epsilon draw happens before the branch and both branches draw
            // exactly once, so the random sequence does not depend on which one
            // is taken.
            val picked = if (random() < 0.8 && selected.isNotEmpty()) {
                pickBestFit(available, selected, getPairScore, currentSeason, random, preferences)
            } else {
                pickWeightedAtRandom(available, currentSeason, random, preferences)
            }

            selected.add(picked)
            usedGarmentIds.add(picked.id)
        }

        if (selected.isEmpty()) continue

        val score = scoreOutfit(selected, getPairScore, currentSeason, preferences)
        val name = selected.joinToString(" + ") { g ->
            g.subcategory?.takeIf { it.isNotEmpty() } ?: g.category
        }
        candidates.add(ScoredOutfit(garments = selected, score = score, name = name))
    }

    // Sort by score and return the top N, avoiding duplicate combinations.
    val ranked = candidates.sortedByDescending { it.score }

    val seen = mutableSetOf<String>()
    val results = mutableListOf<ScoredOutfit>()
    for (c in ranked) {
        val key = c.garments.map { it.id }.sorted().joinToString(",")
        if (seen.add(key)) {
            results.add(c.copy(score = normalizeOutfitScore(c.score)))
            if (results.size >= count) break
        }
    }

    return results
}
