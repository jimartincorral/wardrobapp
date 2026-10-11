package com.wardrobapp.domain

import kotlin.math.abs
import kotlin.math.exp
import kotlinx.serialization.Serializable

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

/**
 * What an outfit is built around: the garments that cover the body.
 *
 * Every outfit is one of these with the same finishing on top -- shoes, a layer
 * when the season wants one, and an accessory (see [FINISHING_SLOTS]). It used
 * to be a draw from fifteen templates, `{top, bottom}` and `{top, bottom,
 * shoes}` and the rest, weighted towards the ones with shoes. That made the
 * shape of an outfit a lottery: whether it came with shoes, a coat or a belt
 * was decided by the ticket, not by the wardrobe or the weather, so two
 * suggestions side by side often disagreed about what an outfit even was. And
 * the weights could not fix it, because the ranking undid them --
 * [scoreOutfit] averages over pairs, so adding shoes to a top and a bottom
 * that go well together could only pull the average down, and the bare pair
 * kept winning. Building every candidate to one shape means the ranking
 * compares like with like, and the only thing left to vary is which garments.
 *
 * The weights say how often each base is drawn, and are the shares the
 * templates gave them: separates were six templates of fifteen and the
 * heaviest-weighted, dresses four, the sets the rest. A wardrobe without a
 * base's garments never draws it.
 */
internal enum class OutfitBase(val slots: List<OutfitSlot>, val weight: Double) {
    SEPARATES(listOf(OutfitSlot.TOPS, OutfitSlot.BOTTOMS), 6.0),
    DRESS(listOf(OutfitSlot.DRESSES), 4.0),
    ACTIVEWEAR(listOf(OutfitSlot.ACTIVEWEAR_SETS), 2.0),
    LOUNGEWEAR(listOf(OutfitSlot.LOUNGEWEAR_SETS), 1.0),
}

/**
 * What finishes any base, in the order it is chosen.
 *
 * Shoes first, so the layer and the accessory are picked against an outfit
 * that already has its shoes on. Each is filled when the wardrobe has one that
 * suits (see [finishingCandidates]) and left out quietly when it does not: a
 * wardrobe with no shoes in it still gets outfits, and the gaps screen is
 * where it hears about the shoes.
 */
private val FINISHING_SLOTS = listOf(OutfitSlot.SHOES, OutfitSlot.OUTERWEAR, OutfitSlot.ACCESSORIES)

/**
 * Accessories that are never part of how an outfit looks. A wallet is owned
 * and carried, and suggesting one says nothing about the outfit; it would
 * also take the one accessory slot from a belt or a bag that does.
 */
private val UNSEEN_ACCESSORIES = setOf("Wallet")

/** Accessories that are worn for the cold, and look wrong without it. */
private val COLD_WEATHER_ACCESSORIES = setOf("Gloves", "Scarf")

/**
 * The seasons an outfit is being dressed for: the ones asked for, or the one
 * it is. All-season is no season at all here -- it says nothing about whether
 * it is cold.
 */
private fun seasonsInPlay(currentSeason: Season, preferences: SuggestionPreferences?): Set<Season> =
    (preferences?.seasons ?: emptyList()).filter { it != Season.ALL_SEASON }.toSet()
        .ifEmpty { setOf(currentSeason) }

/**
 * Whether two garments could be worn on the same kind of day: true unless
 * both have an opinion and the opinions never meet.
 */
private fun canShareADay(garment: Garment, other: Garment): Boolean =
    sharesAnOccasion(garment.occasions(), other.occasions()) != false

/**
 * The garments that may finish [base] in [slot], or none when the slot is
 * better left empty.
 *
 * A finishing garment has to be dressed for the same kind of day as some part
 * of the base, which settles most of what would otherwise need a rule: no
 * occasion an accessory is made for is sport or lounging, so a track suit and
 * a pair of pyjamas go without one; a robe suits pyjamas and a coat does not.
 * And it has to suit the season, as the engine reads a season everywhere else
 * ([matchesSeason]): a pair of sandals tagged for summer is not finishing a
 * winter outfit.
 *
 * Then a rule per slot:
 *  - Shoes are part of every outfit. When nothing in the wardrobe suits, any
 *    pair beats none -- trainers with a cocktail dress is an outfit somebody
 *    can fix by swapping the shoes, and one with no shoes reads as unfinished.
 *    Except when the base is for lounging and nothing else: there is no shoe
 *    for a dressing gown, and suggesting boots with pyjamas would be worse
 *    than leaving the feet bare.
 *  - A layer whenever the outfit is for anything but high summer. Not a coin
 *    toss for spring and autumn, which is what the templates made it: those
 *    are the seasons people go out in a jacket, and an outfit that only
 *    sometimes has one was exactly the inconsistency being fixed.
 *  - One accessory, whenever one suits -- the choice the wardrobe's owner made
 *    over a setting or leaving it to the occasion. Not one nobody sees (a
 *    wallet), and not gloves or a scarf outside autumn and winter.
 */
private fun finishingCandidates(
    slot: OutfitSlot,
    pool: List<Garment>,
    base: List<Garment>,
    currentSeason: Season,
    preferences: SuggestionPreferences?,
): List<Garment> {
    val seasons = seasonsInPlay(currentSeason, preferences)
    val suitable = pool.filter { candidate ->
        matchesSeason(candidate, currentSeason, preferences?.seasons) &&
            (base.isEmpty() || base.any { canShareADay(candidate, it) })
    }

    return when (slot) {
        OutfitSlot.SHOES -> {
            val lounging = base.isNotEmpty() && base.all { it.occasions() == listOf(Occasion.LOUNGE) }
            when {
                suitable.isNotEmpty() -> suitable
                lounging -> emptyList()
                else -> pool
            }
        }
        OutfitSlot.OUTERWEAR -> if (seasons.all { it == Season.SUMMER }) emptyList() else suitable
        OutfitSlot.ACCESSORIES -> {
            val cold = seasons.any { it == Season.FALL || it == Season.WINTER }
            suitable.filter { accessory ->
                val types = accessory.effectiveSubcategories
                types.none { it in UNSEEN_ACCESSORIES } &&
                    (cold || types.none { it in COLD_WEATHER_ACCESSORIES })
            }
        }
        else -> pool
    }
}

/**
 * Draw a base, in proportion to its weight.
 *
 * One call to [random], as the template draw it replaces was: the epsilon-greedy
 * step downstream assumes the generator advances the same number of times
 * whichever branch a run takes, and a draw that sometimes stepped twice would
 * make a seeded run unreproducible.
 */
private fun pickBase(bases: List<OutfitBase>, random: () -> Double): OutfitBase {
    var ticket = random() * bases.sumOf { it.weight }
    for (base in bases) {
        ticket -= base.weight
        if (ticket <= 0) return base
    }
    // Only reachable through floating-point drift at the very end of the wheel.
    return bases.last()
}

@Serializable

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
    /**
     * Breadth over the best: for a session that rates outfits to teach the
     * engine, rather than one that wants to wear something.
     *
     * The ordinary draw picks the best fit four times in five and ranks the
     * candidates by score, which is right for the outfits tab and wrong for
     * training: after a handful of ratings it keeps reaching for the pair it
     * has learned, with a different third garment each time, and the person
     * is asked about the same two things ten times over. Exploring halves the
     * best-fit share, and prefers candidates that bring a garment nothing in
     * [alreadySeen] or this batch has shown yet -- so ten outfits walk across
     * the wardrobe, and a second round with the first as [alreadySeen] keeps
     * walking. The shape of an outfit is left alone (see [OutfitBase]): what
     * a rating teaches is which garments go together, and an outfit missing
     * its shoes would only teach that it looked unfinished.
     */
    val explore: Boolean = false,
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
    /**
     * What the reader's ratings say about outfits like this one; see
     * TasteModel. Zero until there are enough ratings to say anything, and
     * either sign after: this is the term that carries a dislike of patterned
     * pairs, or a liking for the dressed-up, to garments never rated.
     */
    val taste: Double = 0.0,
    /**
     * How close the outfit comes to the looks the reader saved and the
     * outfits they rated well, by the style model's eye; see StyleTaste.
     * Zero where there is no model, no looks, or no garment it has seen.
     */
    val style: Double = 0.0,
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
    taste: TasteModel? = null,
    style: StyleLookup? = null,
    styleTaste: FloatArray? = null,
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

    // What this reader thinks of outfits like this one, from every rating so
    // far. Weighted like a learned pair, because it is the same kind of claim
    // made about outfits in general rather than about these two garments.
    val tasteScore = taste?.let { it.score(outfitFeatures(garments)) * TASTE_WEIGHT } ?: 0.0

    // And what the style model makes of it against the looks the reader
    // likes, where there is a model and a taste to compare with.
    val styleScore = if (style != null && styleTaste != null) {
        styleScore(garments.map { style.vector(it.id) }, styleTaste) * STYLE_WEIGHT
    } else {
        0.0
    }

    return OutfitScore(
        total = learnedScore + affinity + season + occasion + coherence + harmony + loud + tasteScore + styleScore,
        learnedPairs = learnedScore,
        garmentAffinity = affinity,
        season = season,
        occasion = occasion,
        coherence = coherence,
        harmony = harmony,
        loudColours = loud,
        taste = tasteScore,
        style = styleScore,
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
    /** It is the kind of outfit this reader has rated well: see TasteModel. */
    TASTE,
    /** It looks like the looks the reader saved: see StyleTaste. */
    STYLE,
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
    // The same shape as the learned claim: no ceiling, and a clear positive
    // is what earns a mention. Half a star's worth, in the model's units.
    OutfitReason.TASTE to score.taste.takeIf { it >= TASTE_WEIGHT * 0.25 },
    // A cosine: 0.3 and up is a resemblance worth naming in this space, where
    // two garments of the same kind sit around 0.6 and unrelated ones near 0.
    OutfitReason.STYLE to score.style.takeIf { it >= STYLE_WEIGHT * 0.3 },
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

/**
 * How much the taste model's opinion counts.
 *
 * Its prediction is a normalised rating, -1 to 1 at the extremes and much
 * less than that for most outfits once ridge regression has had its say, so
 * at 2 it is level with a strongly learned pair at the extremes and a tilt
 * the rest of the time -- which is what a claim about outfits in general
 * should be against a claim about these exact garments.
 */
private const val TASTE_WEIGHT = 2.0

/**
 * How much the style model's opinion counts: a cosine, -1 to 1, so at 2 it
 * is level with the taste model and with a strongly learned pair at the
 * extremes. In practice a cosine sits within a few tenths of zero for most
 * outfits and climbs for the ones that look like the saved looks, which is
 * a tilt towards them rather than a veto on everything else.
 */
private const val STYLE_WEIGHT = 2.0

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
    taste: TasteModel?,
    style: StyleLookup?,
    styleTaste: FloatArray?,
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

        // Steered by taste as well as scored by it at the end, for the reason
        // the loud-colour rule is: an outfit the model will rank down is a draw
        // wasted on building it.
        val tasteScore = taste?.let { it.score(outfitFeatures(selected + candidate)) * TASTE_WEIGHT } ?: 0.0
        val styleScore = if (style != null && styleTaste != null) {
            styleScore((selected + candidate).map { style.vector(it.id) }, styleTaste) * STYLE_WEIGHT
        } else {
            0.0
        }

        val total = pairScoreSum + harmony + coherence + affinity -
            wouldShout * LOUD_COLOUR_PENALTY +
            contextScore(candidate, currentSeason, preferences) * 1.5 +
            tasteScore + styleScore

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
    /**
     * What the reader's ratings say about outfits in general, fitted from all
     * of them; null while there are too few. See TasteModel.
     */
    val taste: TasteModel? = null,
    /**
     * The style model's vector for a garment, where the Home Assistant app
     * has embedded it, and the reader's taste in the same space; see
     * StyleTaste. Null where there is no model, which is every phone that
     * does not sync and every wardrobe before its first embedding.
     */
    val style: StyleLookup? = null,
    val styleTaste: FloatArray? = null,
)

/**
 * Generate outfit suggestions from an explicit context.
 *
 * Uses epsilon-greedy: best-scoring picks [BEST_FIT_SHARE] of the time, random
 * for variety the rest; half and half when exploring.
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
    val taste = context.taste
    val style = context.style
    val styleTaste = context.styleTaste

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

    // Viable bases are the ones we have garments for. With seed garments,
    // narrow further to bases that can hold every seeded slot: a seeded top
    // needs separates, while a seeded pair of shoes or a belt finishes any base.
    val viableBases = OutfitBase.entries.filter { base ->
        base.slots.all { slot -> !bySlot[slot].isNullOrEmpty() } &&
            seedSlots.all { slot -> slot in base.slots || slot in FINISHING_SLOTS }
    }

    if (viableBases.isEmpty()) return emptyList()

    val candidates = mutableListOf<ScoredOutfit>()
    val attempts = suggestionAttempts(count, garments.size)

    for (i in 0 until attempts) {
        val base = pickBase(viableBases, random)

        val selected = seedGarments.toMutableList()
        val usedGarmentIds = seedGarments.map { it.id }.toMutableSet()

        for (slot in base.slots + FINISHING_SLOTS) {
            // A seeded garment already fills its slot. Without this the loop
            // filled it again -- seeding a top produced outfits containing two
            // tops, because usedGarmentIds blocks repeating a garment but not
            // repeating a slot.
            if (seedSlots.contains(slot)) continue

            val pool = (bySlot[slot] ?: emptyList<Garment>())
                .filter { !usedGarmentIds.contains(it.id) }
            val available = if (slot in base.slots) {
                pool
            } else {
                finishingCandidates(
                    slot,
                    pool,
                    base = selected.filter { garment -> garmentSlots(garment).any { it in base.slots } },
                    currentSeason = currentSeason,
                    preferences = preferences,
                )
            }
            if (available.isEmpty()) continue

            // The epsilon draw happens before the branch and both branches draw
            // exactly once, so the random sequence does not depend on which one
            // is taken.
            val bestFitShare = if (options.explore) EXPLORING_BEST_FIT_SHARE else BEST_FIT_SHARE
            val picked = if (random() < bestFitShare && selected.isNotEmpty()) {
                pickBestFit(
                    available,
                    selected,
                    getPairScore,
                    currentSeason,
                    random,
                    preferences,
                    learned,
                    taste,
                    style,
                    styleTaste,
                )
            } else {
                pickWeightedAtRandom(available, currentSeason, random, preferences)
            }

            selected.add(picked)
            usedGarmentIds.add(picked.id)
        }

        if (selected.isEmpty()) continue

        val score = scoreOutfit(selected, getPairScore, currentSeason, preferences, learned, taste, style, styleTaste)
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

    // The garments the reader has already been shown, in [alreadySeen] or in
    // this batch so far; what an exploring pass wants to get past.
    val seenGarments = options.alreadySeen.flatten().toMutableSet()

    // Passes over the same ranking, each looser than the last: when exploring,
    // outfits that bring an unseen garment first; then fresh outfits; then the
    // ones already shown if there were still not enough. A single pass that
    // skipped what was seen would answer a small wardrobe with fewer outfits
    // every time the button was pressed, which is a worse failure than a
    // repeat.
    val passes = buildList {
        if (options.explore) add(Pass.NEW_GARMENT)
        add(Pass.FRESH)
        add(Pass.ANY)
    }
    for (pass in passes) {
        for (c in ranked) {
            val ids = c.garments.map { it.id }
            val key = key(ids)
            if (pass != Pass.ANY && key in alreadySeen) continue
            if (pass == Pass.NEW_GARMENT && ids.all { it in seenGarments }) continue
            if (!seen.add(key)) continue

            seenGarments += ids
            results.add(c.copy(score = normalizeOutfitScore(c.score)))
            if (results.size >= count) return results
        }
    }

    return results
}

/** Which candidates a selection pass accepts; see the loop in [buildSuggestions]. */
private enum class Pass { NEW_GARMENT, FRESH, ANY }

/** How often a slot is filled by the best fit rather than a weighted draw: epsilon-greedy's one-minus-epsilon. */
private const val BEST_FIT_SHARE = 0.8

/** The same while exploring (see GenerateSuggestionsOptions.explore): half, for breadth. */
private const val EXPLORING_BEST_FIT_SHARE = 0.5
