package com.wardrobapp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A delete that can be undone: what a garment or outfit takes with it when
 * it goes, every row of it comes back with it, and a sync in between does
 * not win. See RecentlyDeleted for the shape of it.
 */
class UndoDeleteTest {

    private val t1 = "2025-03-01T10:00:00.000Z"
    private val t2 = "2025-03-02T10:00:00.000Z"
    private val t3 = "2025-03-03T10:00:00.000Z"

    private fun database() = JdbcSqlDriver.open(kotlin.io.path.createTempFile(suffix = ".db").toFile()).also { WardrobeSchema.applyTo(it) }

    private fun GarmentWrites.add(id: String, photos: List<String> = listOf("$id.jpg", "${id}_nobg.png"), brand: String? = null) = insert(
        GarmentWrites.NewGarment(
            id = id,
            imageUri = photos.first(),
            imageUris = listOf(photos.first()),
            imageUrisNoBg = photos.drop(1),
            category = "tops",
            colorPrimary = "#112233",
            colorPalette = listOf("#112233"),
            brand = brand,
            now = t1,
        ),
    )

    @Test
    fun `a deleted garment comes back whole, outfits and learned scores included`() {
        val db = database()
        val garments = GarmentWrites(db)
        val outfits = OutfitWrites(db)
        garments.add("shirt", brand = "Acme")
        garments.add("trousers")
        garments.add("shoes")
        outfits.insert(id = "work", name = "Work", garmentIds = listOf("shirt", "trousers"), now = t1)
        outfits.insert(id = "just-shirt", name = "Shirt", garmentIds = listOf("shirt"), now = t1)
        outfits.rate(ratingId = "r1", outfitId = "just-shirt", rating = 4, now = t1)
        outfits.rate(ratingId = "r2", outfitId = "work", rating = 5, now = t1)
        val scoresBefore = db.query("SELECT * FROM garment_pair_scores ORDER BY garment_id_a, garment_id_b")
        val ownScoreBefore = db.query("SELECT * FROM garment_scores WHERE garment_id = 'shirt'")
        assertTrue(scoresBefore.isNotEmpty() && ownScoreBefore.isNotEmpty(), "the ratings taught something to undo")

        val deleted = assertNotNull(garments.remove("shirt", t2))
        assertEquals(listOf("shirt.jpg", "shirt_nobg.png"), deleted.photos)
        assertNull(GarmentQueries(db, "").garment("shirt"))
        assertNull(OutfitQueries(db).outfit("just-shirt"), "an outfit left with nothing went with it")
        assertEquals(listOf("trousers"), OutfitQueries(db).outfit("work")!!.garmentIds)
        assertEquals(2, db.query("SELECT * FROM deletions").size, "the garment and the emptied outfit are both remembered as deleted")

        garments.restore(deleted, t3)

        val back = assertNotNull(GarmentQueries(db, "").garment("shirt"))
        assertEquals("Acme", back.brand)
        assertEquals(listOf("shirt_nobg.png"), back.imageUrisNoBg)
        assertEquals(t3, back.updatedAt, "stamped with the restore, so it outranks the deletion a sync may carry")
        assertEquals(listOf("shirt", "trousers"), OutfitQueries(db).outfit("work")!!.garmentIds)
        assertEquals(t3, db.query("SELECT updated_at FROM outfits WHERE id = 'work'").single()["updated_at"])
        assertEquals(listOf("shirt"), OutfitQueries(db).outfit("just-shirt")!!.garmentIds)
        assertEquals(4, OutfitQueries(db).rating("just-shirt")?.rating, "the emptied outfit came back with its rating")
        assertEquals(scoresBefore, db.query("SELECT * FROM garment_pair_scores ORDER BY garment_id_a, garment_id_b"))
        assertEquals(ownScoreBefore, db.query("SELECT * FROM garment_scores WHERE garment_id = 'shirt'"))
        assertEquals(emptyList(), db.query("SELECT * FROM deletions"), "nothing is remembered as deleted any more")
    }

    @Test
    fun `a sync in between does not delete the garment again`() {
        // The server learns of the delete before the undo; the restored row is
        // newer than the deletion, so the next merge keeps it everywhere.
        val phone = database()
        val server = database()
        GarmentWrites(phone).add("shirt")
        SyncStore(server).mergeWith(SyncStore(phone).snapshot())

        val deleted = assertNotNull(GarmentWrites(phone).remove("shirt", t2))
        SyncStore(server).mergeWith(SyncStore(phone).snapshot())
        assertNull(GarmentQueries(server, "").garment("shirt"))

        GarmentWrites(phone).restore(deleted, t3)
        val answer = SyncStore(server).mergeWith(SyncStore(phone).snapshot())
        SyncStore(phone).mergeWith(answer.merged)

        assertNotNull(GarmentQueries(server, "").garment("shirt"))
        assertNotNull(GarmentQueries(phone, "").garment("shirt"))
        assertTrue(SyncStore(server).snapshot().deletions.none { it.id == "shirt" })
    }

    @Test
    fun `a deleted outfit comes back with its rating`() {
        val db = database()
        GarmentWrites(db).add("shirt")
        val outfits = OutfitWrites(db)
        outfits.insert(id = "o1", name = "One", garmentIds = listOf("shirt"), isPinned = true, now = t1)
        outfits.rate(ratingId = "r1", outfitId = "o1", rating = 3, now = t1)

        val deleted = assertNotNull(outfits.remove("o1", t2))
        assertNull(OutfitQueries(db).outfit("o1"))
        assertNull(OutfitQueries(db).rating("o1"))

        outfits.restore(deleted, t3)
        val back = assertNotNull(OutfitQueries(db).outfit("o1"))
        assertTrue(back.isPinned)
        assertEquals(t3, db.query("SELECT updated_at FROM outfits WHERE id = 'o1'").single()["updated_at"])
        assertEquals(3, OutfitQueries(db).rating("o1")?.rating)
        assertEquals(emptyList(), db.query("SELECT * FROM deletions"))
    }

    @Test
    fun `removing what is not there is nothing to undo`() {
        val db = database()
        assertNull(GarmentWrites(db).remove("nothing", t2))
        assertNull(OutfitWrites(db).remove("nothing", t2))
    }

    @Test
    fun `the memory of recent deletes is bounded, and hands back the photos it lets go of`() {
        val recent = RecentlyDeleted(capacity = 2)
        val db = database()
        val garments = GarmentWrites(db)
        for (id in listOf("a", "b", "c")) garments.add(id)

        assertEquals(emptyList(), recent.put(garments.remove("a", t2)!!))
        assertEquals(emptyList(), recent.put(garments.remove("b", t2)!!))
        assertEquals(listOf("a.jpg", "a_nobg.png"), recent.put(garments.remove("c", t2)!!), "the oldest was pushed out")

        assertNull(recent.takeGarment("a"), "gone for good")
        assertNotNull(recent.takeGarment("b"))
        assertNull(recent.takeGarment("b"), "taken once")
        assertEquals(listOf("c.jpg", "c_nobg.png"), recent.discardGarment("c"))
        assertEquals(emptyList(), recent.discardGarment("c"))
    }
}
