package com.wardrobapp.domain

import kotlin.math.abs

/**
 * What the wardrobe is missing, and the outfits that would prove it.
 *
 * The sampled half of the gaps feature. WardrobeCoverage.kt counts exactly what a
 * wardrobe can finish and which slot is stopping it; this decides *which garment*
 * to want in that slot, and hands back real outfits it would join as the evidence
 * for saying so.
 *
 * Three things make that possible without any data the app does not already hold:
 *
 *  - A garment can be described well enough to be scored by three fields.
 *    [occasionsFor] derives what a garment is for from its type, and
 *    [seasonsForSubcategories] derives when it is worn -- so a category, a type
 *    and a colour is a complete garment as far as every judgement in
 *    OutfitSuggestions.kt is concerned. Nothing here needs a price, a shop, or a
 *    wear log, none of which exist.
 *  - The engine can already build around one garment. [GenerateSuggestionsOptions.seedGarments]
 *    narrows the templates to those calling for the seed's slot and skips
 *    refilling it. That path was written for "build me an outfit around this
 *    shirt", which is the same question asked of a shirt that does not exist yet.
 *  - The claim can be checked. Every outfit reported is a list of garments the
 *    reader owns, scored by the same function that scores what the suggestions
 *    screen shows them.
 *
 * A phantom is judged *conservatively*, and it is worth knowing why: it has no
 * id anybody has rated, so [OutfitScore.learnedPairs] and
 * [OutfitScore.garmentAffinity] are structurally zero for it while the real
 * garments it competes against can earn both. A phantom that still clears the bar
 * cleared it on colour, coherence and season alone.
 *
 * Nothing here is cheap: a full run is on the order of a hundred [buildSuggestions]
 * calls, so it belongs off whatever thread is drawing, cached against the
 * wardrobe's own last-modified time. It is, however, entirely reproducible --
 * [GapContext.newRandom] hands every candidate the same sequence of draws, so a
 * gap that surprises somebody can be investigated rather than argued about.
 */

/** The id a phantom garment carries, matching nothing in any wardrobe. */
const val PHANTOM_GARMENT_ID = "__gap__"

/**
 * How many garments a wardrobe needs before it is told what it is missing.
 *
 * Below this every answer is the same answer -- a wardrobe of four things is
 * missing almost everything -- and dressing "you own very little" up as a
 * specific recommendation would be a worse feature than saying nothing. Coverage
 * counts are still perfectly meaningful at any size; it is only the
 * recommendation that needs a wardrobe to reason about.
 */
const val MIN_WARDROBE_FOR_GAPS = 10

/**
 * A garment that does not exist, described well enough to be scored.
 *
 * Three fields, because three fields is all the judgements need -- see the note
 * at the top of this file. Deliberately not a [Garment]: a phantom is a
 * *description of something to want*, and giving it the same type as a garment
 * somebody owns would make it possible to save one by accident.
 */
data class PhantomGarment(
    val category: String,
    val subcategory: String?,
    val colorPrimary: String,
) {
    /**
     * The phantom as the engine needs to see it.
     *
     * Season tags are derived rather than left empty, so the phantom is scored as
     * the garment somebody would actually end up with: the add form fills those
     * same tags in from the same function. It matters in both directions -- a
     * sandal seeded in December carries a summer tag and loses to a boot on
     * [seasonFit], which is the season argument settling itself rather than
     * needing a rule here.
     */
    fun asGarment(): Garment = Garment(
        id = PHANTOM_GARMENT_ID,
        category = category,
        subcategory = subcategory,
        subcategories = listOfNotNull(subcategory),
        tags = seasonsForSubcategories(listOfNotNull(subcategory)).map { it.tag },
        colorPrimary = colorPrimary,
    )
}

/** Why a gap is being reported, strongest first. */
enum class GapEvidence {
    /**
     * Something filled this slot and was retired, and nothing replaced it.
     *
     * The strongest claim the feature can make, because it is a fact about this
     * wardrobe's own history rather than a judgement about clothes.
     */
    RETIRED_UNREPLACED,

    /** Nothing available fills this slot for this kind of day. */
    NOTHING_FITS,

    /**
     * Something does fill it, and this would still be better than what the
     * suggestions screen is currently able to offer.
     */
    RAISES_THE_BAR,
}

/** A garment worth wanting, and the case for it. */
data class WardrobeGap(
    val want: PhantomGarment,
    val slot: OutfitSlot,
    val occasion: Occasion,
    val season: Season,
    /**
     * How many outfits one garment here would complete, exactly.
     *
     * From the coverage arithmetic rather than from the sampling below, so it is a
     * count and not an estimate, and is not capped by how many outfits were
     * sampled to find the examples.
     */
    val outfitsUnlocked: Long,
    /**
     * Outfits it would join, best first, every one of them made of garments the
     * reader owns.
     *
     * Proof rather than illustration: each of these beat the best outfit the
     * suggestions screen can show today. Can be empty for a slot nothing fills at
     * all, where the arithmetic makes the case by itself.
     */
    val examples: List<ScoredOutfit>,
    val evidence: GapEvidence,
    /** The retired garment this would stand in for, when that is what happened. */
    val replaces: Garment? = null,
    /**
     * Other garment types that would do the job exactly as well.
     *
     * Empty when one candidate genuinely won. Populated far more often than that
     * suggests, because a cold wardrobe ties nearly everywhere -- and a tie is
     * information rather than a nuisance: it means the slot is what is missing and
     * the type is the reader's choice. A screen that showed [want] alone here
     * would be presenting the order of GARMENT_CATEGORIES as advice.
     */
    val alternatives: List<PhantomGarment> = emptyList(),
)

/** Everything the analysis needs from outside itself. */
data class GapContext(
    /**
     * The whole wardrobe, retired garments included.
     *
     * The opposite of what [SuggestionContext] wants, and deliberately: a
     * retired garment nothing replaced is the best gap there is, and it cannot be
     * found in a list it has already been filtered out of. Everything downstream
     * that must not see a retired garment filters for itself.
     */
    val garments: List<Garment>,
    val getPairScore: PairScoreLookup,
    val learned: LearnedPreferences = LearnedPreferences.NONE,
    val currentSeason: Season,
    /**
     * A source of fresh, *identical* random sequences -- one per candidate.
     *
     * A factory rather than a generator, which is the difference between a
     * measurement and a lottery. Sharing one generator across candidates would
     * hand the first candidate draws 1..n and the second draws n+1..2n, so how
     * well a garment scored would depend on where it happened to sit in the
     * candidate list, and reordering the catalogue would change what the app told
     * somebody to buy.
     */
    val newRandom: () -> () -> Double,
)

/**
 * The caps that keep a run affordable.
 *
 * Every one of them is a bound on cost rather than a judgement about clothes, and
 * every one is stated here rather than buried, because a silent cap reads as
 * "this is everything" when it is not.
 */
data class GapOptions(
    /** How many gaps to report at most. */
    val gaps: Int = 3,
    /** How many example outfits to carry per gap. */
    val examplesPerGap: Int = 3,
    /** How many of the worst-covered occasions to work on. */
    val occasions: Int = 2,
    /**
     * How many garments an occasion needs before it is worth advising on.
     *
     * Not a cost bound, unlike the rest of these: it is the fix for the way this
     * feature fails when it is built the obvious way. Ranking occasions purely by
     * how few outfits they complete puts the ones nobody dresses for at the top --
     * a wardrobe of twelve casual clothes and no shoes scores zero for sport and
     * zero for formal, so it gets told to buy a track suit and a cocktail dress
     * while the missing shoes go unmentioned.
     *
     * Three, because it is about where a wardrobe is *trying*: below three
     * garments an occasion is not being dressed for in this app, and advice about
     * it is advice about somebody else's life. The cost is real and worth stating
     * -- somebody who owns two formal things and wants more will not hear about
     * them -- and with no wear log there is nothing that could tell that case
     * apart from not dressing formally at all.
     */
    val minOccasionCommitment: Int = 3,
    /** How many slots to consider within each of them. */
    val slotsPerOccasion: Int = 2,
    /** How many of the wardrobe's own colours to try. */
    val colourAnchors: Int = 6,
    /** How many garment types survive the first pass to have colours swept. */
    val typeFinalists: Int = 3,
    /** How many outfits to sample per candidate when measuring it. */
    val measureOutfits: Int = 12,
    /**
     * How many outfits the suggestions screen shows in a batch.
     *
     * Named separately from [examplesPerGap] even though both are three today,
     * because they are two different facts: this one is the bar a candidate has to
     * clear -- the weakest outfit somebody is actually being offered -- and
     * changing how many examples a gap card carries must not quietly move it.
     * Mirrors [GenerateSuggestionsOptions.count].
     */
    val outfitsOnScreen: Int = 3,
)

/**
 * Colours worth suggesting, most-used first.
 *
 * The wardrobe's own colours, and no invented palette: a colour somebody already
 * owns six things in is a colour they wear, and it harmonises with the rest of
 * the wardrobe by construction. Colors.kt refuses to keep a hardcoded list of
 * hexes for the same reason -- a palette is a taste, and neither module holds one.
 *
 * Near-duplicates are collapsed on [SAME_COLOR_DELTA_E], or a wardrobe of mostly
 * black would spend all six anchors on shades of black and never try the navy.
 * The multi-colour sentinel is dropped: "multicoloured" is not something anybody
 * can go and look for.
 */
internal fun colorAnchors(available: List<Garment>, limit: Int): List<String> {
    val byFrequency = available
        .map { it.primaryColor }
        .filter { parseHexColor(it) != null }
        .groupingBy { it.trim().uppercase() }
        .eachCount()
        .entries
        // Frequency, then the hex itself, so two colours owned in equal number
        // come out in the same order on every run.
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key }

    val anchors = mutableListOf<String>()
    for (colour in byFrequency) {
        val alreadyHaveIt = anchors.any { kept ->
            (colorDistance(kept, colour) ?: Double.MAX_VALUE) < SAME_COLOR_DELTA_E
        }
        if (alreadyHaveIt) continue

        anchors.add(colour)
        if (anchors.size >= limit) break
    }

    return anchors
}

/**
 * Whether this is a garment somebody already has.
 *
 * Same type, same colour. Written out rather than handed to
 * [findDuplicatesAmong], which would have been the obvious reuse and is the wrong
 * tool: its heaviest signal is tag overlap at weight 0.6, and a phantom has no
 * tags anybody chose -- only the season tags derived from its type. Comparing
 * those against a real garment's tags produces a number that means nothing, and
 * it would have decided the answer. What is left once the meaningless signals are
 * dropped is type and colour, so type and colour is what this asks, on
 * Colors.kt's own definition of one colour.
 */
internal fun alreadyOwned(
    type: GarmentType,
    colour: String,
    available: List<Garment>,
): Boolean = available.any { garment ->
    garment.category == type.category &&
        garment.effectiveSubcategories.contains(type.subcategory) &&
        (colorDistance(garment.primaryColor, colour) ?: Double.MAX_VALUE) < SAME_COLOR_DELTA_E
}

/**
 * The outfits a candidate would join that beat what is on offer today.
 *
 * `bar` is the best outfit the suggestions screen can currently show for this
 * occasion, and it has to be *beaten* rather than matched. Both halves of that
 * were mistakes on the way here, and both produced the same bad feature:
 *
 *  - Measured against the *weakest* outfit on offer, everything qualified. A cold
 *    wardrobe ties nearly everywhere -- no pair has been rated, and most colour
 *    pairs land in the same bucket -- so "as good as the third-best thing you own"
 *    is a bar that a navy pair of trousers clears in a wardrobe that already has
 *    four pairs.
 *  - Matched rather than beaten, the same ties let a candidate through for being
 *    indistinguishable from what somebody already owns, which is the definition of
 *    not being a gap.
 *
 * The effect is that [GapEvidence.RAISES_THE_BAR] became rare, and that is the
 * honest answer: a wardrobe with every slot filled usually does not have a gap.
 * What is left fires on the case that deserves it -- a slot filled only by
 * something that goes with nothing.
 *
 * Null means the screen can show nothing at all for this occasion, and then every
 * outfit the candidate makes possible is new by definition.
 */
private fun outfitsAbove(
    context: GapContext,
    available: List<Garment>,
    phantom: PhantomGarment?,
    occasion: Occasion,
    bar: Double?,
    options: GapOptions,
): List<ScoredOutfit> {
    val seed = phantom?.asGarment()

    val outfits = buildSuggestions(
        SuggestionContext(
            // The phantom goes into the pool as well as into the seed, and it has
            // to: `buildSuggestions` keeps only the templates whose every slot it
            // has a garment for, and it reads that from the pool rather than from
            // the seed. Left out, a shoe seeded into a wardrobe with no shoes
            // makes every shoe template unviable and the run comes back empty --
            // which is precisely the wardrobe this feature exists to talk about.
            //
            // Safe against being used twice: the slot loop skips any slot the seed
            // already fills, so the phantom can only reach an outfit through the
            // seed. Asserted in the test, since it is not obvious from here.
            garments = available + listOfNotNull(seed),
            getPairScore = context.getPairScore,
            learned = context.learned,
            currentSeason = context.currentSeason,
            random = context.newRandom(),
        ),
        GenerateSuggestionsOptions(
            count = options.measureOutfits,
            preferences = SuggestionPreferences(occasion = occasion),
            seedGarments = listOfNotNull(seed),
        ),
    )

    return if (bar == null) outfits else outfits.filter { it.score > bar }
}

/**
 * The best outfit the suggestions screen can manage today, as one number.
 *
 * Taken from the batch it would actually show rather than from everything it
 * could sample, so "would unlock" means "would be better than what you are being
 * offered" -- not "is arithmetically possible", which is the difference between a
 * gap worth reporting and a combination nobody wants.
 *
 * The same occasion preference is passed here as to every candidate. The engine
 * rewards an occasion match rather than filtering on it, so neither run is purely
 * an occasion's own outfits -- but both runs are wrong in the same direction,
 * which is what a comparison needs.
 */
private fun bestOnOffer(
    context: GapContext,
    available: List<Garment>,
    occasion: Occasion,
    options: GapOptions,
): Double? = outfitsAbove(context, available, null, occasion, null, options)
    .take(options.outfitsOnScreen)
    .maxOfOrNull { it.score }

/** A candidate and how it did. */
private data class Measured(
    val phantom: PhantomGarment,
    val outfits: List<ScoredOutfit>,
    val alternatives: List<PhantomGarment> = emptyList(),
) {
    val qualifying: Int get() = outfits.size
    val best: Double get() = outfits.firstOrNull()?.score ?: 0.0
}

/** Best first: more outfits beaten, then the strongest single outfit. */
private val BY_MERIT = compareByDescending<Measured> { it.qualifying }.thenByDescending { it.best }

/**
 * The garment to want in one slot, for one kind of day.
 *
 * Two passes over the candidates, because the colours are the expensive
 * dimension: every type is tried once in one colour to find the types worth
 * pursuing, and only the finalists have the whole anchor list swept. A single
 * pass over every type crossed with every colour is the same answer for several
 * times the work.
 */
private fun bestCandidateFor(
    context: GapContext,
    available: List<Garment>,
    occasion: Occasion,
    slot: OutfitSlot,
    anchors: List<String>,
    bar: Double?,
    options: GapOptions,
): Measured? {
    fun measure(type: GarmentType, colour: String): Measured {
        val phantom = PhantomGarment(type.category, type.subcategory, colour)
        return Measured(
            phantom = phantom,
            outfits = outfitsAbove(context, available, phantom, occasion, bar, options),
        )
    }

    // A type is only a candidate in a colour it is not already owned in. A type
    // owned in every colour this wardrobe wears is not a gap, whatever the
    // arithmetic says about the slot.
    val candidates = typesFor(slot, occasion).mapNotNull { type ->
        val fresh = anchors.filter { !alreadyOwned(type, it, available) }
        if (fresh.isEmpty()) null else Candidate(type, fresh)
    }

    val finalists = candidates
        .map { it to measure(it.type, it.freshColours.first()) }
        .sortedWith(compareBy(BY_MERIT) { it.second })
        .take(options.typeFinalists)

    val ranked = finalists
        .flatMap { (candidate, firstColour) ->
            // The first anchor was measured in the pass above; measuring it again
            // would be the same call for the same answer.
            listOf(firstColour) + candidate.freshColours.drop(1).map {
                measure(candidate.type, it)
            }
        }
        .sortedWith(BY_MERIT)

    val winner = ranked.firstOrNull() ?: return null

    // Everything that did exactly as well, by type. Ties are the normal case
    // rather than the exception: heels, flats and loafers all fill the work-shoe
    // slot, and in a wardrobe with nothing rated they score identically because
    // there is genuinely nothing to tell them apart. Whichever the sort happened
    // to put first is the catalogue's order and nothing more, so it is reported as
    // one of several rather than as the answer. The same type in two colours
    // collapses here -- that is one alternative, not two.
    val tied = ranked
        .filter { it.qualifying == winner.qualifying && abs(it.best - winner.best) <= SCORE_TIE }
        .map { it.phantom }
        .distinctBy { it.subcategory }
        .filter { it.subcategory != winner.phantom.subcategory }

    return winner.copy(alternatives = tied)
}

/** Scores within this of each other are the same score. */
private const val SCORE_TIE = 1e-9

/** A garment type, and the colours it is not already owned in. */
private data class Candidate(val type: GarmentType, val freshColours: List<String>)

/**
 * A retired garment that used to fill this slot for this kind of day.
 *
 * The first one found, so the order of [GapContext.garments] decides which is
 * named. :data hands them over most-recently-retired first, because it is the
 * only layer that knows the dates -- there is no such field on a domain
 * [Garment], and there does not need to be for this.
 */
private fun retiredFrom(
    garments: List<Garment>,
    slot: OutfitSlot,
    occasion: Occasion,
): Garment? = garments.firstOrNull { garment ->
    !garment.isAvailable &&
        garment.occasions().contains(occasion) &&
        garmentSlots(garment).contains(slot)
}

/**
 * What this wardrobe is missing, most worth saying first.
 *
 * Reports at most one gap per slot however many occasions want one: three kinds
 * of shoes is one piece of advice repeated, and the second and third crowd out
 * the layer or the bottom that would have been said next.
 */
fun analyzeGaps(context: GapContext, options: GapOptions = GapOptions()): List<WardrobeGap> {
    val available = context.garments.filter { it.isAvailable }
    if (available.size < MIN_WARDROBE_FOR_GAPS) return emptyList()

    val anchors = colorAnchors(available, options.colourAnchors)
    if (anchors.isEmpty()) return emptyList()

    val season = context.currentSeason

    // This season only. A parka reported in July is a true statement and a
    // useless one, and somebody who is told about next winter in midsummer stops
    // reading the section. The coverage grid still holds every season for anybody
    // who wants to go looking.
    val worstFirst = coverageGrid(
        context.garments,
        seasons = listOf(if (season == Season.ALL_SEASON) Season.ALL_SEASON else season),
    )
        // Worst-covered *among the occasions this wardrobe dresses for*. See
        // [GapOptions.minOccasionCommitment] for why the filter comes first: the
        // occasions with the least coverage are usually the ones with no
        // coverage, and those are not gaps.
        .filter { it.eligibleGarments >= options.minOccasionCommitment }
        .take(options.occasions)

    val gaps = mutableListOf<WardrobeGap>()
    val slotsSpokenFor = mutableSetOf<OutfitSlot>()

    for (coverage in worstFirst) {
        val bar = bestOnOffer(context, available, coverage.occasion, options)

        val slots = coverage.slotLift
            .filter { it.outfits > 0 && it.slot !in slotsSpokenFor }
            .take(options.slotsPerOccasion)

        for ((slot, unlocked) in slots) {
            val nothingFillsIt = slot in coverage.emptySlots
            val retired = if (nothingFillsIt) {
                retiredFrom(context.garments, slot, coverage.occasion)
            } else {
                null
            }

            val best = bestCandidateFor(
                context,
                available,
                coverage.occasion,
                slot,
                anchors,
                // No bar at all for a slot nothing fills. Nothing on offer can
                // contain this slot, so every outfit that does is new by
                // definition and there is nothing to compare it against -- and
                // comparing anyway is worse than pointless here, because
                // `scoreOutfit` is blind to completeness. It averages over
                // garments and pairs, so putting the black shoes into a
                // two-garment outfit pulls the colour average down towards
                // neutral and the outfit with shoes on scores *below* the one
                // without. The engine knows this about itself -- it is why
                // `templateWeight` corrects for it in the draw rather than in the
                // score -- and a bar applied here would have quietly filtered out
                // the examples for the strongest gaps the feature can find.
                if (nothingFillsIt) null else bar,
                options,
            ) ?: continue

            // A slot that something already fills has to earn its place with
            // outfits. A slot nothing fills does not: the arithmetic has already
            // made that case, and it is a stronger one than any sample.
            if (!nothingFillsIt && best.qualifying == 0) continue

            slotsSpokenFor.add(slot)
            gaps.add(
                WardrobeGap(
                    want = best.phantom,
                    slot = slot,
                    occasion = coverage.occasion,
                    season = coverage.season,
                    outfitsUnlocked = unlocked,
                    examples = best.outfits.take(options.examplesPerGap),
                    evidence = when {
                        retired != null -> GapEvidence.RETIRED_UNREPLACED
                        nothingFillsIt -> GapEvidence.NOTHING_FITS
                        else -> GapEvidence.RAISES_THE_BAR
                    },
                    replaces = retired,
                    alternatives = best.alternatives,
                )
            )
        }
    }

    return gaps
        .sortedWith(
            compareBy<WardrobeGap> { it.evidence.ordinal }
                .thenByDescending { it.outfitsUnlocked }
        )
        .take(options.gaps)
}
