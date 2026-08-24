package com.wardrobapp.data

import com.wardrobapp.domain.GenerateSuggestionsOptions
import com.wardrobapp.domain.Season
import com.wardrobapp.domain.SuggestionPreferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Loading what the engine needs, then running it.
 *
 * The algorithm itself is covered draw-for-draw against the TypeScript
 * elsewhere. What is checked here is the part only this class decides: which
 * garments a suggestion may draw from, that the learned pair scores are actually
 * loaded, and that a suggestion comes back with the photos a screen needs rather
 * than the narrow type the scoring uses.
 */
class SuggestionsTest {

    private val driver = JdbcSqlDriver.fresh()
    private val imageDirectory = "file:///photos/"
    private val subject = Suggestions(
        GarmentQueries(driver, imageDirectory),
        OutfitQueries(driver),
    )

    @AfterTest
    fun close() = driver.close()

    /** A deterministic stand-in for Math.random, so a run can be repeated. */
    private fun sequence(seed: Long = 1): () -> Double {
        var state = seed
        return {
            state = (state * 1664525 + 1013904223) and 0xFFFFFFFFL
            state.toDouble() / 4294967296.0
        }
    }

    private fun addGarment(
        id: String,
        category: String,
        subcategory: String,
        available: Boolean = true,
        tags: String = "[]",
    ) {
        driver.execute(
            "INSERT INTO garments (id, image_uri, image_uris, category, subcategory, " +
                "subcategories, tags, color_primary, color_palette, is_available, " +
                "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(
                id, "$id.jpg", "[\"$id.jpg\"]", category, subcategory,
                "[\"$subcategory\"]", tags, "#000000", "[\"#000000\"]",
                if (available) 1 else 0, "2026-01-01", "2026-01-01",
            ),
        )
    }

    /** Enough of a wardrobe for the engine to build something from. */
    private fun givenWardrobe() {
        addGarment("top-1", "tops", "T-Shirt")
        addGarment("top-2", "tops", "Shirt")
        addGarment("bottom-1", "bottoms", "Jeans")
        addGarment("bottom-2", "bottoms", "Chinos")
        addGarment("shoes-1", "shoes", "Sneakers")
    }

    @Test
    fun `suggests nothing from an empty wardrobe`() {
        assertEquals(emptyList(), subject.suggest(Season.SUMMER, sequence()))
    }

    @Test
    fun `suggests outfits from what is there`() {
        givenWardrobe()

        val suggestions = subject.suggest(Season.SUMMER, sequence())

        assertTrue(suggestions.isNotEmpty(), "a wardrobe with five garments suggested nothing")
        assertTrue(
            suggestions.all { it.garments.isNotEmpty() },
            "a suggestion came back with no garments in it",
        )
    }

    @Test
    fun `hands back the photos a screen needs, not just the ids`() {
        // The engine works on the domain type, which has no photo at all. A
        // suggestion that reached the screen that way would draw blank tiles.
        givenWardrobe()

        val garment = subject.suggest(Season.SUMMER, sequence()).first().garments.first()

        assertEquals("file:///photos/${garment.id}.jpg", garment.displayImage)
        assertTrue(garment.category.isNotEmpty())
    }

    @Test
    fun `never draws on a garment that is no longer in use`() {
        // The decision this class owns. A retired garment still has rows, still
        // has pair scores, and would otherwise score exactly as well as it used
        // to -- so it has to be excluded here or it comes back in a suggestion.
        addGarment("retired-top", "tops", "T-Shirt", available = false)
        addGarment("retired-bottom", "bottoms", "Jeans", available = false)
        givenWardrobe()

        val suggested = subject.suggest(Season.SUMMER, sequence(), GenerateSuggestionsOptions(count = 5))
            .flatMap { outfit -> outfit.garments.map { it.id } }
            .toSet()

        assertEquals(
            emptySet(),
            suggested.filter { it.startsWith("retired-") }.toSet(),
            "a retired garment was suggested",
        )
        assertTrue(suggested.isNotEmpty(), "nothing was suggested at all, so this proved nothing")
    }

    @Test
    fun `repeats itself exactly when the randomness does`() {
        // Everything the engine sees arrives as an argument, so two runs from the
        // same draws have to agree -- which is what makes a surprising
        // suggestion something you can go back and look at.
        givenWardrobe()

        val first = subject.suggest(Season.SUMMER, sequence(seed = 7))
        val second = subject.suggest(Season.SUMMER, sequence(seed = 7))

        assertEquals(
            first.map { it.name to it.garments.map { g -> g.id } },
            second.map { it.name to it.garments.map { g -> g.id } },
        )
    }

    @Test
    fun `loads the learned pair scores`() {
        // Without them every pair scores zero and the ratings the user gave do
        // nothing at all -- silently, since suggestions still appear.
        givenWardrobe()
        val unscored = subject.suggest(Season.SUMMER, sequence(seed = 3))

        // A strong preference between two garments the engine would not
        // otherwise favour together.
        driver.execute(
            "INSERT INTO garment_pair_scores (garment_id_a, garment_id_b, score) VALUES (?, ?, ?)",
            listOf("top-2", "bottom-2", 50.0),
        )
        val scored = subject.suggest(Season.SUMMER, sequence(seed = 3))

        assertTrue(
            unscored.map { it.garments.map { g -> g.id } } !=
                scored.map { it.garments.map { g -> g.id } },
            "a heavily weighted pair changed nothing, so the scores are not being read",
        )
    }

    @Test
    fun `passes the caller's preferences through to the engine`() {
        // The filters on the screen have to reach the scoring, or the chips are
        // decoration.
        addGarment("sporty", "activewear", "Yoga Pants")
        addGarment("formal-top", "tops", "Blouse")
        addGarment("formal-shoes", "shoes", "Heels")
        givenWardrobe()

        val forSport = subject.suggest(
            Season.SUMMER,
            sequence(seed = 11),
            GenerateSuggestionsOptions(
                count = 3,
                preferences = SuggestionPreferences(occasion = com.wardrobapp.domain.Occasion.SPORT),
            ),
        )
        val forFormal = subject.suggest(
            Season.SUMMER,
            sequence(seed = 11),
            GenerateSuggestionsOptions(
                count = 3,
                preferences = SuggestionPreferences(occasion = com.wardrobapp.domain.Occasion.FORMAL),
            ),
        )

        assertTrue(
            forSport.map { it.garments.map { g -> g.id } } !=
                forFormal.map { it.garments.map { g -> g.id } },
            "asking for sport and asking for formal produced the same outfits",
        )
    }

    @Test
    fun `asks for as many suggestions as it was told to`() {
        givenWardrobe()

        assertEquals(1, subject.suggest(Season.SUMMER, sequence(), GenerateSuggestionsOptions(count = 1)).size)
    }
}
