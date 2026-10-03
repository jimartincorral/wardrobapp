package com.wardrobapp.data

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The merge, alone: what two copies of a wardrobe become. */
class WardrobeSyncTest {

    private fun garment(id: String, updatedAt: String, brand: String? = null, photo: String = "$id.jpg") = GarmentRecord(
        id = id,
        imageUri = photo,
        imageUriNoBg = null,
        imageUris = listOf(photo),
        imageUrisNoBg = emptyList(),
        category = "tops",
        subcategory = null,
        subcategories = emptyList(),
        tags = emptyList(),
        brand = brand,
        colorPrimary = "#112233",
        colorSecondary = null,
        colorPalette = listOf("#112233"),
        size = null,
        purchaseDate = null,
        isAvailable = true,
        unavailableDate = null,
        createdAt = "2025-01-01T00:00:00.000Z",
        updatedAt = updatedAt,
    )

    private fun outfit(id: String, updatedAt: String?, vararg garmentIds: String, name: String = id) = SyncOutfit(
        id = id,
        name = name,
        garmentIds = garmentIds.toList(),
        occasion = null,
        season = null,
        createdAt = "2025-01-01T00:00:00.000Z",
        updatedAt = updatedAt,
        isSuggested = false,
        isPinned = false,
        isArchived = false,
    )

    private fun rating(outfitId: String, stars: Int, at: String) =
        SyncRating(id = "r-$outfitId-$at", outfitId = outfitId, rating = stars, feedback = null, ratedAt = at)

    private fun snapshot(
        garments: List<GarmentRecord> = emptyList(),
        outfits: List<SyncOutfit> = emptyList(),
        ratings: List<SyncRating> = emptyList(),
        deletions: List<Deletion> = emptyList(),
    ) = WardrobeSnapshot(garments, outfits, ratings, deletions)

    private val t1 = "2025-03-01T10:00:00.000Z"
    private val t2 = "2025-03-02T10:00:00.000Z"
    private val t3 = "2025-03-03T10:00:00.000Z"

    @Test
    fun `what only one side has, both have`() {
        val merged = merge(snapshot(garments = listOf(garment("a", t1))), snapshot(garments = listOf(garment("b", t1))))
        assertEquals(listOf("a", "b"), merged.garments.map { it.id })
    }

    @Test
    fun `the later edit wins, whole`() {
        val phone = snapshot(garments = listOf(garment("a", t2, brand = "Phone")))
        val server = snapshot(garments = listOf(garment("a", t1, brand = "Server")))

        assertEquals("Phone", merge(phone, server).garments.single().brand)
        assertEquals("Phone", merge(server, phone).garments.single().brand)
    }

    @Test
    fun `a deletion after the last edit deletes it everywhere`() {
        val phone = snapshot(deletions = listOf(Deletion(DeletionKind.GARMENT, "a", t2)))
        val server = snapshot(garments = listOf(garment("a", t1)))

        val merged = merge(phone, server)

        assertEquals(emptyList(), merged.garments)
        assertEquals(listOf(Deletion(DeletionKind.GARMENT, "a", t2)), merged.deletions)
    }

    @Test
    fun `an edit after the deletion brings it back, and the deletion is forgotten`() {
        // The newest thing anybody did to the garment was to change it.
        val phone = snapshot(deletions = listOf(Deletion(DeletionKind.GARMENT, "a", t1)))
        val server = snapshot(garments = listOf(garment("a", t2, brand = "Kept")))

        val merged = merge(phone, server)

        assertEquals("Kept", merged.garments.single().brand)
        assertEquals(emptyList(), merged.deletions)
    }

    @Test
    fun `a deletion and an edit at the same moment is a deletion`() {
        val merged = merge(
            snapshot(deletions = listOf(Deletion(DeletionKind.GARMENT, "a", t1))),
            snapshot(garments = listOf(garment("a", t1))),
        )
        assertEquals(emptyList(), merged.garments)
    }

    @Test
    fun `two different edits at the same moment pick the same one whichever side merges`() {
        val one = snapshot(garments = listOf(garment("a", t1, brand = "One")))
        val two = snapshot(garments = listOf(garment("a", t1, brand = "Two")))

        assertEquals(merge(one, two), merge(two, one))
    }

    @Test
    fun `a garment deleted on one side leaves the outfits the other side put it in`() {
        // The phone deleted the shirt; the server, not knowing, built an
        // outfit with it. Neither side ever saw both.
        val phone = snapshot(
            garments = listOf(garment("trousers", t1)),
            deletions = listOf(Deletion(DeletionKind.GARMENT, "shirt", t3)),
        )
        val server = snapshot(
            garments = listOf(garment("shirt", t1), garment("trousers", t1)),
            outfits = listOf(outfit("both", t2, "shirt", "trousers"), outfit("just-shirt", t2, "shirt")),
        )

        val merged = merge(phone, server)

        assertEquals(listOf("trousers"), merged.outfits.single { it.id == "both" }.garmentIds)
        // Emptied, it goes as removeGarment sends an emptied outfit, deleted
        // when the garment that emptied it was.
        assertTrue(merged.outfits.none { it.id == "just-shirt" })
        assertTrue(Deletion(DeletionKind.OUTFIT, "just-shirt", t3) in merged.deletions)
    }

    @Test
    fun `an outfit stamped before sync existed counts from when it was made`() {
        val old = outfit("o", updatedAt = null, "a", name = "Old")
        val edited = outfit("o", updatedAt = t1, "a", name = "Edited")

        val merged = merge(
            snapshot(garments = listOf(garment("a", t1)), outfits = listOf(old)),
            snapshot(garments = listOf(garment("a", t1)), outfits = listOf(edited)),
        )

        assertEquals("Edited", merged.outfits.single().name)
    }

    @Test
    fun `the latest rating of an outfit wins, and a deleted outfit takes its rating with it`() {
        val garments = listOf(garment("a", t1))
        val phone = snapshot(
            garments = garments,
            outfits = listOf(outfit("kept", t1, "a"), outfit("gone", t1, "a")),
            ratings = listOf(rating("kept", 2, t1), rating("gone", 5, t1)),
        )
        val server = snapshot(
            garments = garments,
            outfits = listOf(outfit("kept", t1, "a")),
            ratings = listOf(rating("kept", 5, t2)),
            deletions = listOf(Deletion(DeletionKind.OUTFIT, "gone", t2)),
        )

        val merged = merge(phone, server)

        assertEquals(listOf(rating("kept", 5, t2)), merged.ratings)
    }

    @Test
    fun `what a side has to change is what differs from the merge, and nothing once it matches`() {
        val phone = snapshot(garments = listOf(garment("a", t1), garment("b", t1)))
        val server = snapshot(
            garments = listOf(garment("a", t2, brand = "Newer")),
            deletions = listOf(Deletion(DeletionKind.GARMENT, "b", t2)),
        )
        val merged = merge(phone, server)

        val changes = changesTo(phone, merged)

        assertEquals(listOf("Newer"), changes.garments.map { it.brand })
        assertEquals(listOf(Deletion(DeletionKind.GARMENT, "b", t2)), changes.removed)
        assertTrue(changesTo(merged, merged).isEmpty)
    }

    @Test
    fun `merging is commutative, idempotent and settles, for any two wardrobes`() {
        // Random wardrobes sharing a small pool of ids, so that the same row
        // turns up on both sides, edited, deleted and resurrected, with ties.
        val random = Random(20261003)
        repeat(2000) { round ->
            val a = randomSnapshot(random)
            val b = randomSnapshot(random)
            val merged = merge(a, b)

            assertEquals(merged, merge(b, a), "round $round: not commutative")
            assertEquals(merged, merge(merged, merged), "round $round: not idempotent")
            // Each side applying its changes arrives at the merge, and a second
            // sync between the two finds nothing left to do.
            assertEquals(merged, merge(merged, a), "round $round: a second sync changed something")
            assertEquals(merged, merge(merged, b), "round $round: a second sync changed something")
            // Nothing in the merge points at something that is not there.
            val garmentIds = merged.garments.map { it.id }.toSet()
            val outfitIds = merged.outfits.map { it.id }.toSet()
            assertTrue(merged.outfits.all { o -> o.garmentIds.isNotEmpty() && o.garmentIds.all { it in garmentIds } }, "round $round")
            assertTrue(merged.ratings.all { it.outfitId in outfitIds }, "round $round")
            assertTrue(merged.deletions.none { it.kind == DeletionKind.GARMENT && it.id in garmentIds }, "round $round")
        }
    }

    @Test
    fun `phones syncing through the server all end with the same wardrobe`() {
        // Every phone syncs with the server, never with another phone, so the
        // server is where copies meet, one sync at a time. What has to hold is
        // that once each phone has synced after the last change, all of them
        // and the server hold one wardrobe, and syncing again changes nothing.
        //
        // Not that the order of syncs never matters: a garment deleted on one
        // phone and edited on another is trimmed out of the outfits that held
        // it when the deletion is merged, and coming back later does not put
        // it back in them -- as deleting a garment on one phone already does.
        // That makes the merge lossy, so merge(merge(a, b), c) and
        // merge(a, merge(b, c)) can differ; the server's one-at-a-time order is
        // what gives the answer.
        val random = Random(4)
        repeat(500) { round ->
            var server = randomSnapshot(random)
            val phones = MutableList(3) { randomSnapshot(random) }

            fun sync(phone: Int) {
                val merged = merge(phones[phone], server)
                server = merged
                phones[phone] = merged
            }

            for (phone in phones.indices.shuffled(random)) sync(phone)
            // A second round, so the phones that synced early catch up.
            for (phone in phones.indices.shuffled(random)) sync(phone)

            for (phone in phones) assertEquals(server, phone, "round $round: a phone disagrees with the server")
            for (phone in phones.indices) {
                val before = server
                sync(phone)
                assertEquals(before, server, "round $round: a settled sync changed something")
            }
        }
    }

    private fun randomSnapshot(random: Random): WardrobeSnapshot {
        val times = listOf(t1, t2, t3)
        val garmentIds = listOf("g1", "g2", "g3", "g4")
        val garments = garmentIds.filter { random.nextBoolean() }
            .map { garment(it, times.random(random), brand = listOf("A", "B", null).random(random)) }
        val outfits = listOf("o1", "o2", "o3").filter { random.nextBoolean() }.mapNotNull { id ->
            val held = garmentIds.filter { random.nextInt(3) == 0 }
            if (held.isEmpty()) null else outfit(id, listOf(null, t1, t2, t3).random(random), *held.toTypedArray())
        }
        val ratings = outfits.filter { random.nextBoolean() }.map { rating(it.id, random.nextInt(1, 6), times.random(random)) }
        val deletions = garmentIds.filter { random.nextInt(4) == 0 }.map { Deletion(DeletionKind.GARMENT, it, times.random(random)) } +
            listOf("o1", "o2", "o3").filter { random.nextInt(4) == 0 }.map { Deletion(DeletionKind.OUTFIT, it, times.random(random)) }
        return WardrobeSnapshot(garments, outfits, ratings, deletions)
    }
}
