package com.wardrobapp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sync against real databases: what a wardrobe reads as, and what applying a
 * merge does to one. Every test runs against both schemas that exist on
 * phones, as the write paths' tests do.
 */
class SyncStoreTest {

    private val t1 = "2025-03-01T10:00:00.000Z"
    private val t2 = "2025-03-02T10:00:00.000Z"
    private val t3 = "2025-03-03T10:00:00.000Z"

    private fun GarmentWrites.add(id: String, now: String = t1, photos: List<String> = listOf("$id.jpg"), brand: String? = null) =
        insert(
            GarmentWrites.NewGarment(
                id = id,
                imageUri = photos.first(),
                imageUris = photos,
                category = "tops",
                colorPrimary = "#112233",
                colorPalette = listOf("#112233"),
                brand = brand,
                now = now,
            ),
        )

    /** Two databases, a phone's and a server's, synced the way the two sides will sync. */
    private fun sync(phone: SqlDriver, server: SqlDriver): Pair<List<String>, List<String>> {
        val phoneStore = SyncStore(phone)
        val serverStore = SyncStore(server)
        val phoneSnapshot = phoneStore.snapshot()
        val serverSnapshot = serverStore.snapshot()
        val merged = merge(phoneSnapshot, serverSnapshot)
        return serverStore.apply(changesTo(serverSnapshot, merged)) to phoneStore.apply(changesTo(phoneSnapshot, merged))
    }

    @Test
    fun `a wardrobe reads as what the screens see, with photos by name`() {
        for ((schema, open) in JdbcSqlDriver.bothSchemas()) {
            val driver = open()
            GarmentWrites(driver).add("a", photos = listOf("file:///data/garment-images/a.jpg"))
            OutfitWrites(driver).insert(id = "o", name = "Friday", garmentIds = listOf("a"), now = t1)
            OutfitWrites(driver).rate(ratingId = "r1", outfitId = "o", rating = 2, now = t1)
            OutfitWrites(driver).rate(ratingId = "r2", outfitId = "o", rating = 5, now = t2)

            val snapshot = SyncStore(driver).snapshot()

            assertEquals(listOf("a.jpg"), snapshot.garments.single().imageUris, schema)
            assertEquals(t1, snapshot.outfits.single().updatedAt, schema)
            assertEquals(5, snapshot.ratings.single().rating, "$schema: the latest rating is the one sent")
        }
    }

    @Test
    fun `every change stamps the outfit, and every deletion is remembered`() {
        for ((schema, open) in JdbcSqlDriver.bothSchemas()) {
            val driver = open()
            val garments = GarmentWrites(driver)
            val outfits = OutfitWrites(driver)
            garments.add("shirt")
            garments.add("trousers")
            outfits.insert(id = "both", name = "Both", garmentIds = listOf("shirt", "trousers"), now = t1)
            outfits.insert(id = "alone", name = "Alone", garmentIds = listOf("shirt"), now = t1)

            outfits.setPinned("both", true, t2)
            assertEquals(t2, SyncStore(driver).snapshot().outfits.single { it.id == "both" }.updatedAt, schema)

            garments.delete("shirt", t3)

            val snapshot = SyncStore(driver).snapshot()
            assertEquals(listOf("trousers"), snapshot.outfits.single { it.id == "both" }.garmentIds, schema)
            assertEquals(t3, snapshot.outfits.single { it.id == "both" }.updatedAt, "$schema: losing a garment is a change")
            assertEquals(
                setOf(Deletion(DeletionKind.GARMENT, "shirt", t3), Deletion(DeletionKind.OUTFIT, "alone", t3)),
                snapshot.deletions.toSet(),
                "$schema: the garment and the outfit it emptied are remembered",
            )
        }
    }

    @Test
    fun `applying a merge makes a wardrobe the merge, and applying it again does nothing`() {
        for ((schema, open) in JdbcSqlDriver.bothSchemas()) {
            val phone = open()
            val server = JdbcSqlDriver.fresh()

            GarmentWrites(phone).add("phone-only", brand = "Phone")
            GarmentWrites(phone).add("shared", now = t2, brand = "Newer on the phone")
            GarmentWrites(server).add("shared", now = t1, brand = "Older on the server")
            GarmentWrites(server).add("server-only")
            OutfitWrites(server).insert(id = "o", name = "Server outfit", garmentIds = listOf("server-only", "shared"), now = t1)
            OutfitWrites(server).rate(ratingId = "r", outfitId = "o", rating = 4, now = t1)

            sync(phone, server)

            val phoneSnapshot = SyncStore(phone).snapshot()
            assertEquals(SyncStore(server).snapshot(), phoneSnapshot, "$schema: the two sides differ after a sync")
            assertEquals(listOf("phone-only", "server-only", "shared"), phoneSnapshot.garments.map { it.id }, schema)
            assertEquals("Newer on the phone", phoneSnapshot.garments.single { it.id == "shared" }.brand, schema)
            assertEquals(4, OutfitQueries(phone).rating("o")?.rating, schema)

            // Nothing left to do: a second sync changes neither side.
            val again = merge(SyncStore(phone).snapshot(), SyncStore(server).snapshot())
            assertTrue(changesTo(SyncStore(phone).snapshot(), again).isEmpty, schema)
            assertTrue(changesTo(SyncStore(server).snapshot(), again).isEmpty, schema)
        }
    }

    @Test
    fun `a rating from the other side is learned from here too`() {
        for ((schema, open) in JdbcSqlDriver.bothSchemas()) {
            val phone = open()
            val server = JdbcSqlDriver.fresh()
            for (driver in listOf(phone, server)) {
                GarmentWrites(driver).add("a")
                GarmentWrites(driver).add("b")
            }
            OutfitWrites(server).insert(id = "o", name = "Pair", garmentIds = listOf("a", "b"), now = t1)
            OutfitWrites(server).rate(ratingId = "r", outfitId = "o", rating = 5, now = t2)

            sync(phone, server)

            // The same pair score the server learned, from the same single
            // rating, rather than nothing because the phone only received it.
            val learned = phone.query("SELECT score, wear_count FROM garment_pair_scores")
            assertEquals(server.query("SELECT score, wear_count FROM garment_pair_scores"), learned, schema)
            assertEquals(1, learned.size, schema)
        }
    }

    @Test
    fun `an outfit changed through a sync keeps the rating it already had`() {
        // Writing the row with INSERT OR REPLACE would delete it first, and with
        // foreign keys on -- as they are on the phone and the server -- the
        // delete cascades to its rating, which the sync has no reason to write
        // back because it did not change.
        val phone = JdbcSqlDriver.open(kotlin.io.path.createTempFile(suffix = ".db").toFile())
            .also { WardrobeSchema.applyTo(it) }
        val server = JdbcSqlDriver.fresh()
        GarmentWrites(server).add("a")
        OutfitWrites(server).insert(id = "o", name = "One", garmentIds = listOf("a"), now = t1)
        OutfitWrites(server).rate(ratingId = "r", outfitId = "o", rating = 4, now = t1)
        sync(phone, server)
        assertEquals(4, OutfitQueries(phone).rating("o")?.rating)

        OutfitWrites(server).update(id = "o", name = "Renamed", garmentIds = listOf("a"), occasion = null, season = null, now = t2)
        sync(phone, server)

        assertEquals("Renamed", OutfitQueries(phone).outfit("o")?.name)
        assertEquals(4, OutfitQueries(phone).rating("o")?.rating, "the rating went with the rewritten outfit")
        phone.close()
    }

    @Test
    fun `a garment deleted on one side goes from the other, photos and learned scores with it`() {
        for ((schema, open) in JdbcSqlDriver.bothSchemas()) {
            val phone = open()
            val server = JdbcSqlDriver.fresh()
            for (driver in listOf(phone, server)) {
                GarmentWrites(driver).add("gone", photos = listOf("gone.jpg", "gone-2.jpg"))
                GarmentWrites(driver).add("kept")
            }
            OutfitWrites(server).insert(id = "o", name = "Pair", garmentIds = listOf("gone", "kept"), now = t1)
            OutfitWrites(server).rate(ratingId = "r", outfitId = "o", rating = 5, now = t1)
            sync(phone, server)

            GarmentWrites(phone).delete("gone", t2)
            val (serverPhotos, _) = sync(phone, server)

            assertNull(GarmentQueries(server, "").garment("gone"), schema)
            assertEquals(setOf("gone.jpg", "gone-2.jpg"), serverPhotos.toSet(), "$schema: the server is told which files to delete")
            assertEquals(emptyList(), server.query("SELECT * FROM garment_pair_scores WHERE garment_id_a = 'gone' OR garment_id_b = 'gone'"), schema)
            assertEquals(listOf("kept"), OutfitQueries(server).outfit("o")?.garmentIds, schema)
        }
    }

    @Test
    fun `a photo a garment stopped using is handed back to be deleted`() {
        val phone = JdbcSqlDriver.fresh()
        val server = JdbcSqlDriver.fresh()
        for (driver in listOf(phone, server)) GarmentWrites(driver).add("a", photos = listOf("old.jpg"))
        GarmentWrites(phone).update("a", GarmentWrites.GarmentEdit(imageUri = "new.jpg", imageUris = listOf("new.jpg")), t2)

        val (serverPhotos, phonePhotos) = sync(phone, server)

        assertEquals(listOf("old.jpg"), serverPhotos)
        assertEquals(emptyList(), phonePhotos)
        assertNotNull(GarmentQueries(server, "").garment("a")?.imageUris?.single { it == "new.jpg" })
    }

    @Test
    fun `a garment brought back by a later edit is no longer remembered as deleted`() {
        val phone = JdbcSqlDriver.fresh()
        val server = JdbcSqlDriver.fresh()
        for (driver in listOf(phone, server)) GarmentWrites(driver).add("a")
        GarmentWrites(phone).delete("a", t2)
        GarmentWrites(server).update("a", GarmentWrites.GarmentEdit(brand = "Still here"), t3)

        sync(phone, server)

        assertEquals("Still here", GarmentQueries(phone, "").garment("a")?.brand)
        assertEquals(emptyList(), SyncStore(phone).snapshot().deletions)
    }

    @Test
    fun `a restored wardrobe replaces the other side's, and other phones follow`() {
        // The server and a second phone both have what was there before the
        // restore, including an edit made after the backup was taken.
        val restored = JdbcSqlDriver.fresh()
        val server = JdbcSqlDriver.fresh()
        val otherPhone = JdbcSqlDriver.fresh()
        for (driver in listOf(server, otherPhone)) {
            GarmentWrites(driver).add("kept", now = t1, brand = "Edited after the backup")
            GarmentWrites(driver).add("added-after-backup", now = t2)
        }
        // The backup: "kept" as it was, and something since deleted.
        GarmentWrites(restored).add("kept", now = t1, brand = "As in the backup")
        GarmentWrites(restored).add("deleted-since", now = t1)

        val restoredAt = "2025-03-10T10:00:00.000Z"
        SyncStore(restored).stampAll(restoredAt)
        val result = SyncStore(server).replaceWith(SyncStore(restored).snapshot(), restoredAt)

        val onServer = SyncStore(server).snapshot()
        assertEquals(listOf("deleted-since", "kept"), onServer.garments.map { it.id })
        assertEquals("As in the backup", onServer.garments.single { it.id == "kept" }.brand)
        assertEquals(listOf("added-after-backup.jpg"), result.photosNoLongerUsed)
        assertTrue(Deletion(DeletionKind.GARMENT, "added-after-backup", restoredAt) in onServer.deletions)

        // The other phone's next ordinary sync arrives at the restored wardrobe
        // rather than bringing its older edits back.
        sync(otherPhone, server)
        assertEquals(onServer.garments, SyncStore(otherPhone).snapshot().garments)
        assertEquals(SyncStore(server).snapshot(), SyncStore(otherPhone).snapshot())
    }

    @Test
    fun `an edit made after the restore still wins`() {
        val restored = JdbcSqlDriver.fresh()
        val server = JdbcSqlDriver.fresh()
        GarmentWrites(restored).add("a", now = t1, brand = "Restored")
        SyncStore(restored).stampAll(t2)
        SyncStore(server).replaceWith(SyncStore(restored).snapshot(), t2)

        GarmentWrites(server).update("a", GarmentWrites.GarmentEdit(brand = "Edited later"), t3)
        sync(restored, server)

        assertEquals("Edited later", GarmentQueries(restored, "").garment("a")?.brand)
    }
}
