package com.wardrobapp.domain

import com.wardrobapp.domain.Parity.double
import com.wardrobapp.domain.Parity.objects
import com.wardrobapp.domain.Parity.sameNumber
import com.wardrobapp.domain.Parity.string
import com.wardrobapp.domain.Parity.stringOrNull
import com.wardrobapp.domain.Parity.strings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The suggestion engine, compared to the TypeScript draw for draw.
 *
 * The engine takes its randomness as a parameter, so a run is reproducible --
 * which is what makes this possible at all. Both sides step the same LCG, so an
 * agreeing outfit list means the two implementations made every identical choice
 * along the way: the same template, the same epsilon branch, the same
 * tie-break, the same roulette slot.
 */
class OutfitSuggestionParityTest {

    /**
     * The same linear congruential generator the dump script runs.
     *
     * State and output are exact: the widest intermediate (state * 1664525) stays
     * under 2^53, so this is bit-identical to the TypeScript rather than merely
     * similar. Masking with 0xFFFFFFFF is the `% 4294967296` on the other side.
     */
    private fun lcg(seed: Long): () -> Double {
        var state = seed and 0xFFFFFFFFL
        return {
            state = (state * 1664525 + 1013904223) and 0xFFFFFFFFL
            state.toDouble() / 4294967296.0
        }
    }

    private data class Wardrobe(
        val garments: List<Garment>,
        val pairScores: Map<String, Double>,
    )

    private fun loadWardrobe(): Wardrobe {
        val stream = javaClass.getResourceAsStream("/parity/wardrobe.json")
            ?: fail("Missing /parity/wardrobe.json. Generate it with: npm run parity:dump")

        val root = Json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject

        val garments = (root["garments"] ?: fail("no garments")).let { arr ->
            (arr as kotlinx.serialization.json.JsonArray).map { element ->
                val g = element.jsonObject
                Garment(
                    id = g.string("id"),
                    category = g.string("category"),
                    subcategory = g.stringOrNull("subcategory"),
                    subcategories = g.strings("subcategories"),
                    tags = g.strings("tags"),
                    colorPrimary = g.string("color_primary"),
                    colorPalette = g.strings("color_palette"),
                    size = g.stringOrNull("size"),
                )
            }
        }

        val pairScores = (root["pairScores"] ?: fail("no pairScores")).jsonObject
            .mapValues { (_, v) -> v.jsonPrimitive.content.toDouble() }

        return Wardrobe(garments, pairScores)
    }

    private fun preferencesFrom(json: JsonObject): SuggestionPreferences? {
        if (json.isEmpty()) return null

        val seasons = json["seasons"]?.let { element ->
            (element as kotlinx.serialization.json.JsonArray).map { season ->
                val tag = season.jsonPrimitive.content
                Season.fromTag(tag) ?: fail("Unknown season tag in fixture: $tag")
            }
        } ?: emptyList()

        val occasion = json["occasion"]?.let { element ->
            val id = element.jsonPrimitive.content
            Occasion.fromId(id) ?: fail("Unknown occasion in fixture: $id")
        }

        return SuggestionPreferences(seasons = seasons, occasion = occasion)
    }

    @Test
    fun `matches the TypeScript engine on every scenario`() {
        val wardrobe = loadWardrobe()
        val byId = wardrobe.garments.associateBy { it.id }
        val cases = Parity.load("suggestions.jsonl")
        val failures = mutableListOf<String>()

        for (case in cases) {
            val scenario = case.double("scenario").toInt()
            val seed = case.double("seed").toLong()
            val wardrobeSize = case.double("wardrobeSize").toInt()
            val seedIds = case.strings("seedIds")
            val withLearning = case["withLearning"]!!.jsonPrimitive.content == "true"
            val preferences = preferencesFrom(case["preferences"]!!.jsonObject)
            val currentSeason = Season.fromTag(case.string("currentSeason"))
                ?: fail("Unknown currentSeason in fixture")

            val garments = wardrobe.garments.take(wardrobeSize)
            val getPairScore = if (withLearning) {
                PairScoreLookup { a, b -> wardrobe.pairScores[pairKey(a, b)] ?: 0.0 }
            } else {
                PairScoreLookup { _, _ -> 0.0 }
            }

            val actual = buildSuggestions(
                SuggestionContext(
                    garments = garments,
                    getPairScore = getPairScore,
                    currentSeason = currentSeason,
                    random = lcg(seed),
                ),
                GenerateSuggestionsOptions(
                    count = 3,
                    preferences = preferences,
                    seedGarments = garments.filter { it.id in seedIds },
                ),
            )

            val expected = case.objects("outfits")

            if (actual.size != expected.size) {
                failures += "scenario $scenario: expected ${expected.size} outfits, got ${actual.size}"
                continue
            }

            for ((rank, expectedOutfit) in expected.withIndex()) {
                val actualOutfit = actual[rank]

                val expectedIds = expectedOutfit.strings("ids")
                val actualIds = actualOutfit.garments.map { it.id }
                if (expectedIds != actualIds) {
                    failures += "scenario $scenario rank $rank: expected $expectedIds, got $actualIds"
                    continue
                }

                val expectedScore = expectedOutfit.double("score")
                if (!sameNumber(expectedScore, actualOutfit.score)) {
                    failures += "scenario $scenario rank $rank: expected score $expectedScore, got ${actualOutfit.score}"
                }

                val expectedName = expectedOutfit.string("name")
                if (expectedName != actualOutfit.name) {
                    failures += "scenario $scenario rank $rank: expected name '$expectedName', got '${actualOutfit.name}'"
                }
            }

            // A garment must not be reachable through byId if the fixture and
            // wardrobe ever fall out of step.
            for (id in seedIds) {
                if (byId[id] == null) failures += "scenario $scenario: seed id $id not in wardrobe fixture"
            }
        }

        assertTrue(
            failures.isEmpty(),
            "${failures.size} divergences across ${cases.size} scenarios:\n" +
                failures.take(20).joinToString("\n")
        )
    }

    @Test
    fun `the two LCG implementations agree`() {
        // If this drifts, every scenario above fails for a reason that has
        // nothing to do with the engine. Pinned against values computed from the
        // recurrence directly, so it fails loudly rather than confusingly.
        val random = lcg(1)
        val first = List(3) { random() }

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
                sameNumber(expected[i], first[i]),
                "LCG draw $i: expected ${expected[i]}, got ${first[i]}"
            )
        }
    }

    @Test
    fun `the fixture exercises the engine rather than skirting it`() {
        val cases = Parity.load("suggestions.jsonl")
        val outfits = cases.flatMap { it.objects("outfits") }

        assertTrue(cases.size >= 300, "expected a broad scenario set, got ${cases.size}")
        assertTrue(outfits.size >= 500, "expected plenty of outfits, got ${outfits.size}")

        // Templates of different lengths must all appear, or whole branches of
        // the slot-filling loop go unchecked.
        val outfitSizes = outfits.map { it.strings("ids").size }.toSet()
        assertTrue(outfitSizes.containsAll(setOf(2, 3)), "outfit sizes seen: $outfitSizes")

        // Distinct combinations, not the same outfit 900 times: that is what the
        // reachability fix was about.
        val distinct = outfits.map { it.strings("ids").sorted() }.toSet()
        assertTrue(distinct.size >= 50, "only ${distinct.size} distinct outfits in the corpus")

        assertTrue(
            cases.any { it["withLearning"]!!.jsonPrimitive.content == "true" },
            "learned pair scores are never exercised"
        )
        assertTrue(
            cases.any { it.strings("seedIds").isNotEmpty() },
            "seeded suggestions are never exercised"
        )
        assertTrue(
            cases.any { it.objects("outfits").isNotEmpty() },
            "every scenario is empty: the corpus proves nothing"
        )
    }
}
