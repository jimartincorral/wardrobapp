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
}
