package com.wardrobapp.domain

import kotlin.math.abs
import kotlin.math.exp

/**
 * Outfit suggestion algorithm.
 *
 * Pure domain logic: no database, no filesystem, no clock, no platform. Every
 * input arrives through [SuggestionContext], so a run is reproducible -- which is
 * what made this portable, and what let it be checked against the
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
internal val OUTFIT_TEMPLATES: List<List<OutfitSlot>> = listOf(
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

/**
 * How often a template is drawn, relative to the others.
 *
 * Templates used to be picked uniformly, and only six of the fifteen include
 * shoes -- so most of what the screen showed was a top and a bottom with nothing
 * on the feet, which reads as an unfinished thought rather than an outfit. These
 * weights are the fix, and they say what "finished" means here: shoes are what
 * turn a pair of garments into something you could walk out in, and a layer or an
 * accessory is what makes it look chosen rather than assembled.
 *
 * A multiplier rather than a rule, because the plain combinations are still real
 * answers -- a dress on its own is an outfit -- and a wardrobe with no shoes in it
 * must still be able to suggest something. Nothing is excluded; the odds move.
 */
private fun templateWeight(template: List<OutfitSlot>): Double {
    var weight = 1.0
    if (template.contains(OutfitSlot.SHOES)) weight *= 3.0
    if (template.any { it == OutfitSlot.OUTERWEAR || it == OutfitSlot.ACCESSORIES }) weight *= 1.5
    return weight
}

/**
 * Draw a template, in proportion to [templateWeight].
 *
 * One call to [random], as the uniform draw it replaces was: the epsilon-greedy
 * step downstream assumes the generator advances the same number of times
 * whichever branch a run takes, and a template draw that sometimes stepped twice
 * would make a seeded run unreproducible.
 */
private fun pickTemplate(
    templates: List<List<OutfitSlot>>,
    random: () -> Double,
): List<OutfitSlot> {
    val weights = templates.map { templateWeight(it) }

    var ticket = random() * weights.sum()
    for (i in templates.indices) {
        ticket -= weights[i]
        if (ticket <= 0) return templates[i]
    }
    // Only reachable through floating-point drift at the very end of the wheel.
    return templates.last()
}

data class ScoredOutfit(
    val garments: List<Garment>,
    val score: Double,
    val name: String,
    /** Why it came up, most telling first. Empty when nothing stood out. */
    val reasons: List<OutfitReason> = emptyList(),
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

internal fun garmentSlots(garment: Garment): List<OutfitSlot> {
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
    /**
     * Outfits already shown, as their garment ids, to be offered last.
     *
     * Nothing remembered what it had just suggested, so tapping the button twice
     * could hand back the same three outfits and read as a broken button. Passed
     * in rather than remembered here, because the engine is pure and "what was on
     * screen a moment ago" is the screen's business.
     *
     * Last rather than never: a wardrobe with four wearable combinations in it
     * would otherwise run out of things to say, and showing a repeat beats showing
     * nothing.
     */
    val alreadySeen: List<List<String>> = emptyList(),
)

/** Whether a garment's tags match the season in play. */
internal fun matchesSeason(
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
 * Whether two garments are dressed for the same kind of day.
 *
 * Null for either side having no opinion, which is not the same as disagreeing:
 * underwear is deliberately for no occasion at all, so a thermal under a shirt
 * must not read as a clash.
 */
private fun sharesAnOccasion(mine: List<Occasion>, theirs: List<Occasion>): Boolean? {
    if (mine.isEmpty() || theirs.isEmpty()) return null
    return mine.any { it in theirs }
}

/**
 * How much of an outfit is dressed for one kind of day.
 *
 * The gap this fills: [occasionFit] scores each garment against the occasion the
 * user *asked* for, and returns nothing at all when they asked for none -- which
 * is most of the time. So the engine had no opinion whatsoever about whether an
 * outfit hung together, and gym shorts under a blazer scored exactly as well as a
 * shirt with chinos.
 *
 * Counted pairwise rather than as one intersection across the whole outfit,
 * because an intersection is unanimous-or-nothing: a blazer with jeans and
 * trainers shares no single occasion between all three, and it is a real outfit
 * people wear. Pairwise says "two of these three agree", which is the honest
 * reading.
 *
 * Positive only, like [colorHarmonyScore] and for the same reason: this table is
 * a rough guide to what a garment is for, not an authority, and an engine that
 * punished on its word would refuse combinations that are perfectly good.
 */
private fun occasionCoherence(garments: List<Garment>): Double {
    val occasions = garments.map { it.occasions() }

    var agreeing = 0
    var pairs = 0
    for (i in occasions.indices) {
        for (j in i + 1 until occasions.size) {
            val shared = sharesAnOccasion(occasions[i], occasions[j]) ?: continue
            if (shared) agreeing++
            pairs++
        }
    }

    return if (pairs == 0) 0.0 else agreeing.toDouble() / pairs
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

/**
 * What an outfit scored, and what each part of the judgement contributed.
 *
 * The parts are kept rather than summed away because the screen has a use for
 * them -- "you rated these together" is worth saying, and "it scored 0.81" is
 * not -- and because a term that is computed and never added to the total is a
 * change that alters nothing while looking like it does. Both readings come off
 * the same object.
 *
 * Every field is already weighted, so they sum to [total].
 */
data class OutfitScore(
    val total: Double,
    val learnedPairs: Double,
    /**
     * How the garments themselves have been rated, whatever they were worn with.
     *
     * Zero for an outfit of garments nobody has rated, rather than negative:
     * absence of evidence is not a complaint. Can be negative for garments that
     * keep turning up in outfits rated badly, which is the point -- a pair score
     * only knows about combinations somebody has already been shown.
     */
    val garmentAffinity: Double,
    val season: Double,
    val occasion: Double,
    val coherence: Double,
    val harmony: Double,
    /** Negative or zero: the only part of the judgement that takes away. */
    val loudColours: Double,
)

/**
 * Score a candidate outfit.
 *
 * Internal rather than private so a test can score two outfits and compare them,
 * and so a test can check that each part reaches the total.
 */
internal fun scoreOutfit(
    garments: List<Garment>,
    getPairScore: PairScoreLookup,
    currentSeason: Season,
    preferences: SuggestionPreferences?,
    learned: LearnedPreferences = LearnedPreferences.NONE,
): OutfitScore {
    // Pair scores from learning
    var pairTotal = 0.0
    var pairCount = 0
    for (i in garments.indices) {
        for (j in i + 1 until garments.size) {
            pairTotal += getPairScore.score(garments[i].id, garments[j].id)
            pairCount++
        }
    }
    // Weight learned preferences heavily.
    val learnedScore = if (pairCount > 0) (pairTotal / pairCount) * 3 else 0.0

    // Season and occasion, each counted exactly once. Season used to be added
    // here at weight 1.0 and again inside contextScore at weight 1.2, giving it
    // an effective weight of 2.2 -- more than colour harmony, and more than
    // intended.
    val season = (garments.sumOf { seasonFit(it, currentSeason, preferences) } / garments.size) * 1.0
    val occasion = (garments.sumOf { occasionFit(it, preferences) } / garments.size) * 1.2

    // Whether the garments agree with *each other* about what kind of day this
    // is, which is a different question from whether they suit the day that was
    // asked for -- and the only one that has an answer when nothing was asked.
    val coherence = occasionCoherence(garments) * 1.0

    // Colour harmony
    var harmonyTotal = 0.0
    var harmonyCount = 0
    for (i in garments.indices) {
        for (j in i + 1 until garments.size) {
            harmonyTotal += colorHarmonyScore(
                garments[i].primaryColor,
                garments[j].primaryColor,
                learned.colorRelationship,
            )
            harmonyCount++
        }
    }
    val harmony = if (harmonyCount > 0) (harmonyTotal / harmonyCount) * 1.5 else 0.0

    // What the garments are worth on their own. Averaged rather than summed so a
    // five-garment outfit is not favoured over a three-garment one for having
    // more chances to be liked, and damped by how little is known about each --
    // a garment nobody has rated contributes nothing rather than dragging the
    // outfit down.
    val affinity = garments.map { learned.garment(it.id)?.trusted ?: 0.0 }.average() *
        GARMENT_AFFINITY_WEIGHT

    // The one term that subtracts. Harmony is an average over pairs and cannot
    // see how many loud colours there are in total, only how each pair of them
    // gets on -- so this is counted over the outfit rather than over its pairs.
    val loud = -excessLoudColours(garments) * LOUD_COLOUR_PENALTY

    return OutfitScore(
        total = learnedScore + affinity + season + occasion + coherence + harmony + loud,
        learnedPairs = learnedScore,
        garmentAffinity = affinity,
        season = season,
        occasion = occasion,
        coherence = coherence,
        harmony = harmony,
        loudColours = loud,
    )
}

/**
 * Why an outfit was suggested, in terms worth reading.
 *
 * An enum rather than a sentence because the words belong in :app, where the
 * reader's language is known -- and because "the colours work" is a claim this
 * module is entitled to make while the wording of it is not its business.
 */
enum class OutfitReason {
    /** These garments have been rated well together before. */
    LEARNED,
    /** The colours go together. */
    COLOURS,
    /** It suits the occasion that was asked for. */
    OCCASION,
    /** It suits the season. */
    SEASON,
    /** The garments are dressed for the same kind of day. */
    COHERENT,
}

/** Above this share of its own possible value, a term is worth mentioning. */
private const val REASON_THRESHOLD = 0.6

/**
 * The two or three things most worth saying about why this outfit came up.
 *
 * Ordered by how much each contributed rather than by a fixed precedence, so the
 * reason given is the reason it won -- a learned pair beating colour is worth
 * saying, and so is the reverse. Capped, because a list of five reasons is a
 * paragraph nobody reads and every outfit would show most of them.
 *
 * A term has to clear [REASON_THRESHOLD] of what it could have contributed to be
 * named at all: an outfit scraping half marks on colour has not earned "the
 * colours work", and saying so of everything would make the words worthless.
 */
fun outfitReasons(score: OutfitScore, limit: Int = 2): List<OutfitReason> = listOf(
    // Learned pairs have no ceiling -- a rating folds into a running average that
    // sits within about 0.5 either way, so 3x that is around 1.5 -- and being
    // rated well at all is the strongest thing that can be said about an outfit.
    // Both learned signals, as one claim: "you have rated these well" is one
    // thing to a reader, and saying it twice would push a real second reason off
    // a line that only holds two.
    OutfitReason.LEARNED to (score.learnedPairs + score.garmentAffinity).takeIf { it > 0.3 },
    OutfitReason.COLOURS to score.harmony.takeIf { it >= 1.5 * REASON_THRESHOLD },
    OutfitReason.OCCASION to score.occasion.takeIf { it >= 1.2 * REASON_THRESHOLD },
    OutfitReason.SEASON to score.season.takeIf { it >= 1.0 * REASON_THRESHOLD },
    OutfitReason.COHERENT to score.coherence.takeIf { it >= 1.0 * REASON_THRESHOLD },
)
    .mapNotNull { (reason, value) -> value?.let { reason to it } }
    .sortedByDescending { it.second }
    .take(limit)
    .map { it.first }

/**
 * How many garments may shout their colour before an outfit reads as a costume.
 *
 * Two. One statement colour against neutrals is a considered outfit and two that
 * contrast is a deliberate one; the third is where it stops looking chosen.
 *
 * This exists because harmony is scored pairwise and every pair of it was happy:
 * a contrast scores 0.7, so red with green with gold with blue scored 0.7 across
 * all six pairs -- better than one bold colour with neutrals, which scores 0.5 for
 * every pair it appears in. The arithmetic was right about each pair and wrong
 * about the outfit, which is exactly what a pairwise average cannot see.
 */
private const val LOUD_COLOUR_ALLOWANCE = 2

/** What a loud colour past the allowance costs. */
private const val LOUD_COLOUR_PENALTY = 0.8

/**
 * How much a garment's own record counts.
 *
 * Below the weight on a learned *pair* (3), because a pair is the more specific
 * claim: "these two work together" says more than "you tend to like this shirt".
 * Level with season and coherence, which is about right -- a signal that should
 * tilt a close call rather than decide one on its own.
 */
private const val GARMENT_AFFINITY_WEIGHT = 1.0

/** How many colours past the allowance an outfit shouts in. */
private fun excessLoudColours(garments: List<Garment>): Int =
    (garments.count { isLoudColor(it.primaryColor) } - LOUD_COLOUR_ALLOWANCE).coerceAtLeast(0)

/**
 * How many draws to take before ranking them.
 *
 * The old rule was `min(count * 5, 20)`, which is twenty samples of a wardrobe
 * whatever size it is -- a thin search of two hundred garments, and the reason a
 * large wardrobe kept showing the same corner of itself. There is no I/O in a
 * draw, so the wardrobe's own size is affordable as a term.
 *
 * Bounded at both ends. Twenty is the floor because a small wardrobe still needs
 * enough attempts to find distinct outfits after the duplicates are dropped, and
 * a ceiling exists because this runs while somebody waits: the returns from
 * sampling fall away long before the cost does.
 */
internal fun suggestionAttempts(count: Int, wardrobeSize: Int): Int =
    (count * 5 + wardrobeSize).coerceIn(20, 150)

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
    learned: LearnedPreferences,
): Garment {
    var bestScore = Double.NEGATIVE_INFINITY
    var tied = mutableListOf<Garment>()

    // Once, outside the loop: every candidate is compared against all of these,
    // and `occasions()` walks two lookup tables each time it is asked.
    val selectedOccasions = selected.map { it.occasions() }

    for (candidate in available) {
        val pairScoreSum = selected.sumOf { getPairScore.score(candidate.id, it.id) }
        val harmony = selected.sumOf {
            colorHarmonyScore(candidate.primaryColor, it.primaryColor, learned.colorRelationship)
        }
        // A garment's own record steers the build as well as the ranking: a
        // garment that keeps appearing in outfits rated badly should be reached
        // for less often, not merely scored down once it is already in.
        val affinity = learned.garment(candidate.id)?.trusted ?: 0.0

        // Counted per garment already chosen, the way harmony is, so a candidate
        // that suits three of them beats one that suits one. Weighted a little
        // above a colour match on purpose: wearing gym shorts with a blazer is a
        // worse mistake than wearing two colours that fight.
        val candidateOccasions = candidate.occasions()
        val coherence = selectedOccasions.count {
            sharesAnOccasion(candidateOccasions, it) == true
        }.toDouble()

        // Steered here as well as scored at the end, or the engine would keep
        // assembling four-colour outfits and then ranking them down -- twenty
        // draws spent on candidates it was always going to reject.
        val wouldShout = excessLoudColours(selected + candidate) -
            excessLoudColours(selected)

        val total = pairScoreSum + harmony + coherence + affinity -
            wouldShout * LOUD_COLOUR_PENALTY +
            contextScore(candidate, currentSeason, preferences) * 1.5

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
    /**
     * What else ratings have taught: a garment's own record, and which kinds of
     * colour pairing this wardrobe's owner actually likes.
     *
     * Defaulted to knowing nothing, so a caller that has not wired it up gets
     * exactly the engine that existed before it did.
     */
    val learned: LearnedPreferences = LearnedPreferences.NONE,
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
    val learned = context.learned
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
    val attempts = suggestionAttempts(count, garments.size)

    for (i in 0 until attempts) {
        val template = pickTemplate(viableTemplates, random)

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
                pickBestFit(
                    available,
                    selected,
                    getPairScore,
                    currentSeason,
                    random,
                    preferences,
                    learned,
                )
            } else {
                pickWeightedAtRandom(available, currentSeason, random, preferences)
            }

            selected.add(picked)
            usedGarmentIds.add(picked.id)
        }

        if (selected.isEmpty()) continue

        val score = scoreOutfit(selected, getPairScore, currentSeason, preferences, learned)
        val name = outfitNameFrom(selected.map { garmentLabelFor(it.category, it.subcategory) })
        candidates.add(
            ScoredOutfit(
                garments = selected,
                score = score.total,
                name = name,
                reasons = outfitReasons(score),
            )
        )
    }

    // Sort by score and return the top N, avoiding duplicate combinations.
    val ranked = candidates.sortedByDescending { it.score }

    fun key(ids: List<String>) = ids.sorted().joinToString(",")
    val alreadySeen = options.alreadySeen.map { key(it) }.toSet()

    val seen = mutableSetOf<String>()
    val results = mutableListOf<ScoredOutfit>()

    // Two passes over the same ranking: fresh outfits first, then the ones already
    // shown if there were not enough. A single pass that skipped what was seen
    // would answer a small wardrobe with fewer outfits every time the button was
    // pressed, which is a worse failure than a repeat.
    for (allowRepeats in listOf(false, true)) {
        for (c in ranked) {
            val key = key(c.garments.map { it.id })
            if (!allowRepeats && key in alreadySeen) continue
            if (!seen.add(key)) continue

            results.add(c.copy(score = normalizeOutfitScore(c.score)))
            if (results.size >= count) return results
        }
    }

    return results
}
