package com.wardrobapp.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The suggestion engine.
 *
 * This used to replay 432 recorded runs of the TypeScript engine, draw for draw:
 * both sides stepped the same generator, so an agreeing outfit list meant every
 * intermediate choice matched. That corpus went with the app it was recorded
 * from, and what is left is the set of properties the engine has to hold whatever
 * the draws are -- which is the part worth keeping anyway, since it says why the
 * output is right rather than that it has not changed.
 *
 * The engine takes its randomness as a parameter, so every test here is
 * deterministic without pinning a single expected list.
 */
class OutfitSuggestionsTest {

    /**
     * A linear congruential generator, so a run can be repeated exactly.
     *
     * The widest intermediate (state * 1664525) stays under 2^53, so the
     * arithmetic is exact rather than merely close.
     */
    private fun lcg(seed: Long): () -> Double {
        var state = seed and 0xFFFFFFFFL
        return {
            state = (state * 1664525 + 1013904223) and 0xFFFFFFFFL
            state.toDouble() / 4294967296.0
        }
    }

    private fun garment(
        id: String,
        category: String,
        subcategory: String? = null,
        tags: List<String> = emptyList(),
        color: String = "#000000",
        available: Boolean = true,
    ) = Garment(
        id = id,
        category = category,
        subcategory = subcategory,
        tags = tags,
        colorPrimary = color,
        isAvailable = available,
    )

    /** Enough of a wardrobe to fill the common templates. */
    private val wardrobe = listOf(
        garment("top-white", "tops", "T-Shirt", color = "#FFFFFF"),
        garment("top-navy", "tops", "Shirt", color = "#1F3A93"),
        garment("top-red", "tops", "Blouse", color = "#C0392B"),
        garment("bottom-jeans", "bottoms", "Jeans", color = "#2C3E50"),
        garment("bottom-chinos", "bottoms", "Chinos", color = "#BDC3C7"),
        garment("shoes-white", "shoes", "Sneakers", color = "#FFFFFF"),
        garment("shoes-brown", "shoes", "Boots", color = "#8B4513"),
        garment("coat-wool", "outerwear", "Coat", tags = listOf("winter"), color = "#34495E"),
        garment("coat-light", "outerwear", "Windbreaker", tags = listOf("lightweight")),
        garment("dress-black", "dresses", "Midi Dress", color = "#000000"),
    )

    private fun context(
        garments: List<Garment> = wardrobe,
        season: Season = Season.SPRING,
        seed: Long = 1,
        pairScores: Map<String, Double> = emptyMap(),
    ) = SuggestionContext(
        garments = garments,
        getPairScore = PairScoreLookup { a, b -> pairScores[pairKey(a, b)] ?: 0.0 },
        currentSeason = season,
        random = lcg(seed),
    )

    /**
     * One outfit's raw score, with nothing else to tell outfits apart.
     *
     * No learned pairs, no season asked for, no occasion asked for -- so what is
     * left in the total is colour harmony and whether the garments agree with each
     * other about the occasion.
     */
    private fun scoreOf(garments: List<Garment>): Double = breakdownOf(garments).total

    /** The same, kept whole, for the tests that are about one part of it. */
    private fun breakdownOf(
        garments: List<Garment>,
        pairScores: Map<String, Double> = emptyMap(),
        preferences: SuggestionPreferences? = null,
        learned: LearnedPreferences = LearnedPreferences.NONE,
    ): OutfitScore = scoreOutfit(
        garments = garments,
        getPairScore = PairScoreLookup { a, b -> pairScores[pairKey(a, b)] ?: 0.0 },
        currentSeason = Season.SPRING,
        preferences = preferences,
        learned = learned,
    )

    /** A garment score with enough evidence behind it to be acted on. */
    private fun settled(score: Double, count: Int = 40) = LearnedScore(score, count)

    @Test
    fun `an empty wardrobe suggests nothing`() {
        assertEquals(emptyList(), buildSuggestions(context(garments = emptyList())))
    }

    @Test
    fun `a wardrobe with nothing wearable together suggests nothing`() {
        // Every template needs at least one slot filled, and a wardrobe of
        // accessories fills none of them. Returning an empty list beats
        // returning an outfit of one scarf.
        val accessories = listOf(
            garment("scarf", "accessories", "Scarf"),
            garment("hat", "accessories", "Hat"),
        )

        assertEquals(emptyList(), buildSuggestions(context(garments = accessories)))
    }

    @Test
    fun `it suggests as many outfits as it was asked for`() {
        val suggestions = buildSuggestions(context(), GenerateSuggestionsOptions(count = 4))

        assertEquals(4, suggestions.size)
    }

    @Test
    fun `every suggestion is wearable`() {
        val suggestions = buildSuggestions(context(), GenerateSuggestionsOptions(count = 6))

        for (outfit in suggestions) {
            assertTrue(outfit.garments.isNotEmpty(), "an empty outfit was suggested")

            // No garment twice in one outfit, and nothing from outside the
            // wardrobe it was given.
            val ids = outfit.garments.map { it.id }
            assertEquals(ids.size, ids.toSet().size, "a garment appears twice in ${outfit.name}")
            assertTrue(wardrobe.map { it.id }.containsAll(ids), "an unknown garment appears")

            // One garment per slot: two pairs of shoes is not an outfit.
            val categories = outfit.garments.map { it.category }
            assertEquals(
                categories.size,
                categories.toSet().size,
                "${outfit.name} draws twice from one category",
            )

            assertTrue(outfit.name.isNotBlank(), "an outfit with no name")
            assertTrue(outfit.score in 0.0..1.0, "score out of range: ${outfit.score}")
        }
    }

    @Test
    fun `the same draws give the same suggestions`() {
        // The property the whole engine is built around: randomness is injected,
        // so a run can be reproduced -- which is what let it be compared against
        // another implementation, and what lets a bug be reported at all.
        val first = buildSuggestions(context(seed = 42), GenerateSuggestionsOptions(count = 5))
        val second = buildSuggestions(context(seed = 42), GenerateSuggestionsOptions(count = 5))

        assertEquals(first.map { it.garments.map { g -> g.id } }, second.map { it.garments.map { g -> g.id } })
        assertEquals(first.map { it.score }, second.map { it.score })
    }

    @Test
    fun `different draws give different suggestions`() {
        // Otherwise the test above passes on an engine that ignores its
        // randomness entirely, which is the same result and a different app.
        val fromOne = buildSuggestions(context(seed = 1), GenerateSuggestionsOptions(count = 5))
        val fromTwo = buildSuggestions(context(seed = 999), GenerateSuggestionsOptions(count = 5))

        assertTrue(
            fromOne.map { it.garments.map { g -> g.id } } != fromTwo.map { it.garments.map { g -> g.id } },
            "two seeds produced the same suggestions",
        )
    }

    @Test
    fun `a garment that is no longer available is never suggested`() {
        val retired = wardrobe.map { if (it.id == "top-navy") it.copy(isAvailable = false) else it }

        val suggestions = buildSuggestions(
            context(garments = retired.filter { it.isAvailable }),
            GenerateSuggestionsOptions(count = 8),
        )

        assertTrue(
            suggestions.none { outfit -> outfit.garments.any { it.id == "top-navy" } },
            "a retired garment was suggested",
        )
    }

    @Test
    fun `a learned pair is preferred over an unrated one`() {
        // The point of rating outfits. With one pair rated far above every other,
        // it should show up more often than chance -- checked over many draws
        // rather than one, since 20% of picks are deliberately random.
        val favoured = pairKey("top-white", "bottom-jeans")

        val withLearning = (1..40).flatMap { seed ->
            buildSuggestions(
                context(seed = seed.toLong(), pairScores = mapOf(favoured to 5.0)),
                GenerateSuggestionsOptions(count = 3),
            )
        }
        val withoutLearning = (1..40).flatMap { seed ->
            buildSuggestions(context(seed = seed.toLong()), GenerateSuggestionsOptions(count = 3))
        }

        fun countPair(outfits: List<ScoredOutfit>) = outfits.count { outfit ->
            val ids = outfit.garments.map { it.id }
            "top-white" in ids && "bottom-jeans" in ids
        }

        assertTrue(
            countPair(withLearning) > countPair(withoutLearning),
            "learning changed nothing: ${countPair(withLearning)} vs ${countPair(withoutLearning)}",
        )
    }

    @Test
    fun `a seeded garment is in every outfit built around it`() {
        val seed = wardrobe.single { it.id == "dress-black" }

        val suggestions = buildSuggestions(
            context(),
            GenerateSuggestionsOptions(count = 4, seedGarments = listOf(seed)),
        )

        assertTrue(suggestions.isNotEmpty(), "seeding produced nothing")
        for (outfit in suggestions) {
            assertTrue(
                outfit.garments.any { it.id == "dress-black" },
                "${outfit.name} was built around a garment it does not contain",
            )
        }
    }

    @Test
    fun `an outfit dressed for one kind of day beats one that is not`() {
        // The gap this closes: with no occasion asked for, the engine used to have
        // no opinion at all about whether the garments agreed with each other, so
        // gym shorts under a blazer scored exactly as well as a shirt with chinos.
        val coherent = listOf(
            garment("shirt", "tops", "Shirt"),
            garment("chinos", "bottoms", "Chinos"),
            garment("loafers", "shoes", "Loafers"),
        )
        val mixed = listOf(
            garment("blazer", "midlayer", "Blazer"),
            garment("gym-shorts", "activewear", "Workout Shorts"),
            garment("loafers", "shoes", "Loafers"),
        )

        // Everything else about these two is equal: the same shoes, no learned
        // pairs, one colour throughout, and no season or occasion asked for. The
        // only thing left to tell them apart is whether they agree.
        assertTrue(
            scoreOf(coherent) > scoreOf(mixed),
            "an incoherent outfit scored as well: ${scoreOf(coherent)} vs ${scoreOf(mixed)}",
        )
    }

    @Test
    fun `a garment with no occasion at all is not treated as a clash`() {
        // Underwear is deliberately for no occasion, so a thermal under a shirt
        // has nothing to disagree about -- and must not be scored as if it did,
        // or the engine would learn to avoid a layer that is simply silent.
        val withThermal = listOf(
            garment("shirt", "tops", "Shirt"),
            garment("chinos", "bottoms", "Chinos"),
            garment("thermal", "underwear", "Thermal"),
        )
        val shirtAndChinos = listOf(
            garment("shirt", "tops", "Shirt"),
            garment("chinos", "bottoms", "Chinos"),
        )

        assertEquals(
            scoreOf(shirtAndChinos),
            scoreOf(withThermal),
            absoluteTolerance = 1e-9,
            message = "a silent garment changed the score",
        )
    }

    @Test
    fun `most suggestions come with shoes on`() {
        // Templates used to be drawn uniformly and only six of the fifteen include
        // shoes, so most of what the screen showed was a top and a bottom and
        // nothing on the feet. Counted over many draws, because any single one is
        // allowed to be a dress on its own.
        val outfits = (1..60).flatMap { seed ->
            buildSuggestions(context(seed = seed.toLong()), GenerateSuggestionsOptions(count = 3))
        }

        val shod = outfits.count { outfit -> outfit.garments.any { it.category == "shoes" } }

        // Measured: 160 of 180 with the weights, 110 without. The threshold sits
        // between the two so this fails if the weighting is removed rather than
        // passing on the uniform draw, which already clears half in this wardrobe
        // -- four of its eight viable templates include shoes.
        assertTrue(
            shod * 4 > outfits.size * 3,
            "only $shod of ${outfits.size} suggestions had shoes",
        )
    }

    @Test
    fun `a wardrobe with no shoes in it still suggests something`() {
        // The weights move the odds, they do not exclude: a template that cannot
        // be filled was never viable, and one that can must stay reachable.
        val shoeless = wardrobe.filterNot { it.category == "shoes" }

        val suggestions = buildSuggestions(
            context(garments = shoeless),
            GenerateSuggestionsOptions(count = 3),
        )

        assertTrue(suggestions.isNotEmpty(), "a wardrobe without shoes suggested nothing")
    }

    @Test
    fun `a fourth loud colour costs more than its harmony earns`() {
        // Harmony is an average over pairs and every pair of this is happy: four
        // contrasting colours score 0.7 across all six pairings, which beat one
        // statement colour against neutrals scoring 0.5 for every pair it is in.
        // The arithmetic was right about each pair and wrong about the outfit.
        val shouting = listOf(
            garment("red", "tops", "Shirt", color = "#C0392B"),
            garment("green", "bottoms", "Chinos", color = "#27AE60"),
            garment("gold", "shoes", "Loafers", color = "#DAA520"),
            garment("blue", "outerwear", "Coat", color = "#1F3A93"),
        )
        val oneStatement = listOf(
            garment("red2", "tops", "Shirt", color = "#C0392B"),
            garment("grey", "bottoms", "Chinos", color = "#7F8C8D"),
            garment("white", "shoes", "Loafers", color = "#FFFFFF"),
            garment("black", "outerwear", "Coat", color = "#111111"),
        )

        assertTrue(
            scoreOf(oneStatement) > scoreOf(shouting),
            "four loud colours scored better: ${scoreOf(shouting)} vs ${scoreOf(oneStatement)}",
        )
    }

    @Test
    fun `two loud colours are not penalised`() {
        // The allowance is the point: one statement colour is a considered outfit
        // and two that contrast is a deliberate one. Only the third costs.
        val two = listOf(
            garment("navy", "tops", "Shirt", color = "#1F3A93"),
            garment("red", "bottoms", "Chinos", color = "#C0392B"),
            garment("white", "shoes", "Sneakers", color = "#FFFFFF"),
        )

        assertEquals(0.0, breakdownOf(two).loudColours, absoluteTolerance = 1e-9)
    }

    @Test
    fun `the draw count grows with the wardrobe, within bounds`() {
        // Twenty samples of two hundred garments was a thin search, and the reason
        // a large wardrobe kept showing the same corner of itself.
        assertTrue(
            suggestionAttempts(count = 3, wardrobeSize = 200) >
                suggestionAttempts(count = 3, wardrobeSize = 10),
            "a bigger wardrobe did not earn more draws",
        )

        // Bounded at both ends: a floor so a small wardrobe still finds distinct
        // outfits after duplicates are dropped, a ceiling because somebody waits.
        assertEquals(20, suggestionAttempts(count = 1, wardrobeSize = 1))
        assertEquals(150, suggestionAttempts(count = 3, wardrobeSize = 5000))
    }

    @Test
    fun `an outfit already shown is offered last`() {
        // Tapping the button twice used to be able to hand back the same outfits,
        // which reads as a broken button.
        val first = buildSuggestions(context(seed = 5), GenerateSuggestionsOptions(count = 3))
        val firstIds = first.map { outfit -> outfit.garments.map { it.id } }

        val second = buildSuggestions(
            context(seed = 5),
            GenerateSuggestionsOptions(count = 3, alreadySeen = firstIds),
        )

        assertTrue(second.isNotEmpty(), "avoiding repeats produced nothing")
        assertEquals(
            emptyList(),
            second.map { outfit -> outfit.garments.map { it.id } }.filter { it in firstIds },
            "the same draw offered an outfit it had already shown",
        )
    }

    @Test
    fun `a wardrobe with one outfit in it repeats rather than saying nothing`() {
        // Last, not never. A wardrobe with a single wearable combination would
        // otherwise run out of things to say, and a repeat beats a blank screen.
        val minimal = listOf(
            garment("only-top", "tops", "T-Shirt"),
            garment("only-bottom", "bottoms", "Jeans"),
        )
        val onlyOutfit = listOf(listOf("only-top", "only-bottom"))

        val again = buildSuggestions(
            context(garments = minimal),
            GenerateSuggestionsOptions(count = 3, alreadySeen = onlyOutfit),
        )

        assertEquals(1, again.size, "the only outfit there is was withheld")
    }

    @Test
    fun `every part of the judgement reaches the total`() {
        // The reason the breakdown is returned rather than summed away: a term
        // computed and not added is a change that alters nothing while looking
        // like it does, and the end-to-end output cannot show that because the
        // draw decides what ever gets scored.
        // Every term non-zero, including the one that subtracts: a fixture with no
        // excess loud colours would let the penalty be dropped from the total
        // without this noticing, which is the mistake this test is here to catch.
        val outfit = listOf(
            garment("shirt", "tops", "Shirt", tags = listOf("spring"), color = "#1F3A93"),
            garment("chinos", "bottoms", "Chinos", tags = listOf("spring"), color = "#C0392B"),
            garment("shoes", "shoes", "Loafers", tags = listOf("spring"), color = "#DAA520"),
        )
        val score = breakdownOf(
            outfit,
            pairScores = mapOf(pairKey("shirt", "chinos") to 1.0),
            preferences = SuggestionPreferences(occasion = Occasion.WORK),
        )

        assertEquals(
            score.learnedPairs + score.season + score.occasion +
                score.coherence + score.harmony + score.loudColours,
            score.total,
            absoluteTolerance = 1e-9,
            message = "the parts do not sum to the total",
        )

        // Each of them actually carrying something, or the sum above is a sum of
        // zeroes agreeing with itself.
        assertTrue(score.learnedPairs > 0, "a rated pair contributed nothing")
        assertTrue(score.season > 0, "a seasonal outfit contributed nothing")
        assertTrue(score.occasion > 0, "an occasion that was asked for contributed nothing")
        assertTrue(score.coherence > 0, "agreeing garments contributed nothing")
        assertTrue(score.harmony > 0, "colour contributed nothing")
        assertTrue(score.loudColours < 0, "three loud colours cost nothing")
    }

    @Test
    fun `an outfit says why it came up, strongest reason first`() {
        val rated = listOf(
            garment("shirt", "tops", "Shirt", color = "#1F3A93"),
            garment("chinos", "bottoms", "Chinos", color = "#BDC3C7"),
        )

        // A well-rated pair is the strongest thing that can be said about an
        // outfit, so it leads even where the colours also work.
        val learned = outfitReasons(
            breakdownOf(rated, pairScores = mapOf(pairKey("shirt", "chinos") to 5.0))
        )
        assertEquals(OutfitReason.LEARNED, learned.first())

        // And without a rating it is not claimed. Saying "you rated these" about
        // an outfit nobody has rated is the one reason that would be a lie.
        assertTrue(
            OutfitReason.LEARNED !in outfitReasons(breakdownOf(rated)),
            "an unrated outfit claimed a learned pair",
        )
    }

    @Test
    fun `no more reasons are given than can be read`() {
        // Every term at once, which is a paragraph rather than a reason.
        val everything = listOf(
            garment("shirt", "tops", "Shirt", tags = listOf("spring"), color = "#1F3A93"),
            garment("chinos", "bottoms", "Chinos", tags = listOf("spring"), color = "#BDC3C7"),
        )
        val score = breakdownOf(
            everything,
            pairScores = mapOf(pairKey("shirt", "chinos") to 5.0),
            preferences = SuggestionPreferences(occasion = Occasion.WORK),
        )

        assertTrue(outfitReasons(score).size <= 2, "too many reasons to read")
        assertEquals(3, outfitReasons(score, limit = 3).size)
    }

    @Test
    fun `a summer selection keeps the wool coat out`() {
        // Heavy outerwear in a summer outfit is the one seasonal rule the engine
        // enforces by filtering rather than by scoring.
        val summer = SuggestionPreferences(seasons = listOf(Season.SUMMER))

        val suggestions = buildSuggestions(
            context(season = Season.SUMMER),
            GenerateSuggestionsOptions(count = 8, preferences = summer),
        )

        assertTrue(
            suggestions.none { outfit -> outfit.garments.any { it.id == "coat-wool" } },
            "a wool coat was suggested for summer",
        )
    }

    @Test
    fun `a pair key reads the same in either order`() {
        assertEquals(pairKey("a", "b"), pairKey("b", "a"))
        assertEquals("a|b", pairKey("a", "b"))
    }

    @Test
    fun `the generator produces the sequence its recurrence says it should`() {
        // Pinned against values computed from the recurrence directly, so a
        // mistake here fails on its own rather than as a wrong outfit somewhere.
        val random = lcg(1)
        val drawn = List(3) { random() }

        // state1 = (1*1664525 + 1013904223) mod 2^32 = 1015568748
        // state2 = (1015568748*1664525 + 1013904223) mod 2^32 = 1586005467
        // state3 = (1586005467*1664525 + 1013904223) mod 2^32 = 2165703038
        val expected = listOf(
            1015568748.0 / 4294967296.0,
            1586005467.0 / 4294967296.0,
            2165703038.0 / 4294967296.0,
        )

        for (i in expected.indices) {
            assertTrue(
                abs(expected[i] - drawn[i]) < 1e-12,
                "draw $i: expected ${expected[i]}, got ${drawn[i]}",
            )
        }
    }

    // ---- what a rating teaches beyond the pair it was given to ---------------

    @Test
    fun `a garment nobody has rated is not held against its outfit`() {
        // Absence of evidence is not a complaint. This is the whole reason the
        // term is damped by confidence rather than being a plain average.
        val garments = listOf(garment("a", "tops"), garment("b", "bottoms"))

        assertEquals(0.0, breakdownOf(garments).garmentAffinity, 1e-9)
    }

    @Test
    fun `a garment rated well lifts the outfits it is in`() {
        val garments = listOf(garment("a", "tops"), garment("b", "bottoms"))
        val liked = LearnedPreferences(garment = { id -> settled(1.0).takeIf { id == "a" } })

        assertTrue(breakdownOf(garments, learned = liked).total > breakdownOf(garments).total)
    }

    @Test
    fun `a garment rated badly costs the outfits it is in`() {
        // The point of a garment-level score: a pair score only knows about
        // combinations somebody has already been shown, so a garment you dislike
        // keeps coming up until every one of its pairings has been rated.
        val garments = listOf(garment("a", "tops"), garment("b", "bottoms"))
        val disliked = LearnedPreferences(garment = { id -> settled(-1.0).takeIf { id == "a" } })

        assertTrue(breakdownOf(garments, learned = disliked).total < breakdownOf(garments).total)
    }

    @Test
    fun `one rating does not rearrange the wardrobe`() {
        // A single five-star outfit is not an aesthetic. The same score with one
        // rating behind it must move the total far less than with forty.
        val garments = listOf(garment("a", "tops"), garment("b", "bottoms"))
        val fresh = LearnedPreferences(garment = { LearnedScore(1.0, count = 1) })
        val settledLikes = LearnedPreferences(garment = { settled(1.0) })

        val plain = breakdownOf(garments).total
        val afterOne = breakdownOf(garments, learned = fresh).total - plain
        val afterMany = breakdownOf(garments, learned = settledLikes).total - plain

        assertTrue(afterOne > 0.0)
        assertTrue(afterOne * 3 < afterMany, "one rating counted for too much")
    }

    @Test
    fun `a garment is not liked more for being in a bigger outfit`() {
        // Averaged, not summed: otherwise the term rewards outfits for having
        // more garments, which is a different judgement entirely.
        val liked = LearnedPreferences(garment = { settled(1.0) })
        val two = breakdownOf(listOf(garment("a", "tops"), garment("b", "bottoms")), learned = liked)
        val three = breakdownOf(
            listOf(garment("a", "tops"), garment("b", "bottoms"), garment("c", "shoes")),
            learned = liked,
        )

        assertEquals(two.garmentAffinity, three.garmentAffinity, 1e-9)
    }

    @Test
    fun `a colour pairing rated well is worth more than the default says`() {
        // Somebody who dresses in monochrome should stop being told that two
        // shades of one colour read as unconsidered.
        val same = colorHarmonyScore("#1B2A4A", "#1B2A4A")
        val learnedSame = colorHarmonyScore("#1B2A4A", "#1B2A4A") { relationship ->
            settled(1.0).takeIf { relationship == ColorRelationship.SAME }
        }

        assertTrue(learnedSame > same)
    }

    @Test
    fun `a colour pairing rated badly is worth less`() {
        val contrasting = colorHarmonyScore("#1B2A4A", "#C2410C")
        val learned = colorHarmonyScore("#1B2A4A", "#C2410C") { relationship ->
            settled(-1.0).takeIf { relationship == ColorRelationship.CONTRASTING }
        }

        assertTrue(learned < contrasting)
    }

    @Test
    fun `the default is never entirely discarded`() {
        // Confidence never reaches 1: the hardcoded aesthetics are a prior worth
        // keeping a little of, however much evidence there is.
        val learned = colorHarmonyScore("#1B2A4A", "#1B2A4A") { settled(-1.0, count = 100_000) }

        assertTrue(learned > 0.0, "a learned dislike erased the default entirely")
    }

    @Test
    fun `nothing is learned about a colour that could not be read`() {
        // UNKNOWN means a colour would not parse. How outfits containing an
        // unreadable colour were rated says nothing about colour.
        val unreadable = colorHarmonyScore("not-a-colour", "#1B2A4A") { settled(1.0) }

        assertEquals(colorHarmonyScore("not-a-colour", "#1B2A4A"), unreadable, 1e-9)
    }

    @Test
    fun `an engine told nothing behaves as it did before any of this`() {
        // The default LearnedPreferences knows nothing, so every existing caller
        // gets exactly the engine it had.
        val garments = listOf(garment("a", "tops"), garment("b", "bottoms"), garment("c", "shoes"))

        assertEquals(
            breakdownOf(garments),
            breakdownOf(garments, learned = LearnedPreferences.NONE),
        )
    }

    @Test
    fun `a correction does not count as a second opinion`() {
        // The count is what confidence is built on, so a rating changed from 2 to
        // 5 must leave one rating's worth of evidence, not two.
        val first = foldRatingIntoScore(null, 2)
        val corrected = foldRatingIntoScore(first, 5, previous = 2)

        assertEquals(1, corrected.count)
        assertEquals(foldRatingIntoScore(null, 5).score, corrected.score, 1e-9)
    }

    @Test
    fun `evidence accumulates towards trust, never reaching it`() {
        assertEquals(0.0, learningConfidence(0), 1e-9)
        assertTrue(learningConfidence(1) < learningConfidence(8))
        assertEquals(0.5, learningConfidence(LEARNING_CONFIDENCE_HALFWAY), 1e-9)
        assertTrue(learningConfidence(1_000_000) < 1.0)
    }
}
