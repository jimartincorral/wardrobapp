package com.wardrobapp.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The style model's tables: vectors kept by the photo they came from, and
 * looks that are deleted by tombstone.
 */
class StyleQueriesTest {

    private val driver = JdbcSqlDriver.fresh()
    private val style = StyleQueries(driver)

    @AfterTest
    fun close() = driver.close()

    @Test
    fun `a vector survives the blob and comes back the same`() {
        val vector = FloatArray(512) { it / 512f - 0.5f }

        style.putEmbedding("g1", "a.jpg", "clip-test", vector, "2026-01-01T00:00:00.000Z")

        assertContentEquals(vector, style.embeddings().getValue("g1"))
        assertEquals("a.jpg", style.embeddedPhotos().getValue("g1"))
    }

    @Test
    fun `a garment is embedded once per photo, the latest replacing the last`() {
        style.putEmbedding("g1", "a.jpg", "m", floatArrayOf(1f, 0f), "t1")
        style.putEmbedding("g1", "b.jpg", "m", floatArrayOf(0f, 1f), "t2")

        assertEquals(1, style.embeddings().size)
        assertContentEquals(floatArrayOf(0f, 1f), style.embeddings().getValue("g1"))
        style.deleteEmbedding("g1")
        assertTrue(style.embeddings().isEmpty())
    }

    @Test
    fun `a look is listed newest first, embedded when asked, and gone once deleted`() {
        style.addInspiration("l1", "one.jpg", "2026-01-01T00:00:00.000Z")
        style.addInspiration("l2", "two.jpg", "2026-01-02T00:00:00.000Z")

        assertEquals(listOf("l2", "l1"), style.inspirations().map { it.id })
        assertEquals(listOf("l1", "l2"), style.inspirationsToEmbed().map { it.id })

        style.putInspirationVector("l1", "m", floatArrayOf(0.6f, 0.8f))
        assertEquals(listOf("l2"), style.inspirationsToEmbed().map { it.id })
        assertEquals(1, style.inspirationVectors().size)

        // Deleted by tombstone: gone from every listing, its photo handed back
        // for deleting, and its vector out of the taste.
        assertEquals("one.jpg", style.deleteInspiration("l1", "2026-01-03T00:00:00.000Z"))
        assertNull(style.deleteInspiration("l1", "2026-01-03T00:00:00.000Z"))
        assertEquals(listOf("l2"), style.inspirations().map { it.id })
        assertTrue(style.inspirationVectors().isEmpty())
    }

    @Test
    fun `looks merge like garments, the latest change winning and a deletion carrying`() {
        style.addInspiration("kept", "kept.jpg", "2026-01-01T00:00:00.000Z")
        style.addInspiration("gone", "gone.jpg", "2026-01-01T00:00:00.000Z")
        style.putInspirationVector("kept", "m", floatArrayOf(1f, 0f))

        val theirs = listOf(
            // The same look, unchanged: nothing to do.
            SyncInspiration("kept", "kept.jpg", "2026-01-01T00:00:00.000Z", "2026-01-01T00:00:00.000Z", null),
            // Deleted on the other side, later: deleted here.
            SyncInspiration("gone", "gone.jpg", "2026-01-01T00:00:00.000Z", "2026-01-01T00:00:00.000Z", "2026-01-02T00:00:00.000Z"),
            // New there, with its vector: arrives whole.
            SyncInspiration("new", "new.jpg", "2026-01-03T00:00:00.000Z", "2026-01-03T00:00:00.000Z", null, vector = listOf(0f, 1f)),
        )
        val merge = style.mergeInspirations(theirs)

        assertTrue(merge.changedAnything)
        assertEquals(listOf("gone.jpg"), merge.photosNoLongerUsed)
        assertEquals(listOf("kept.jpg", "new.jpg"), merge.photosInUse)
        assertEquals(listOf("new", "kept"), style.inspirations().map { it.id })
        // The vector this side had stays; the deleted look's is gone; the new one's came along.
        assertEquals(listOf(listOf(0f, 1f), listOf(1f, 0f)), style.inspirationVectors().map { it.toList() }.sortedBy { it[0] })
        assertEquals(listOf("gone", "kept", "new"), merge.merged.map { it.id })
        assertEquals("2026-01-02T00:00:00.000Z", merge.merged.single { it.id == "gone" }.deletedAt)

        // Merging what came back changes nothing more: the two sides agree.
        assertFalse(style.mergeInspirations(merge.merged).changedAnything)
    }

    @Test
    fun `the vectors the other side lacks are the ones its photos do not match`() {
        style.putEmbeddings(
            listOf(
                SyncEmbedding("g1", "one.jpg", "m", listOf(1f, 0f)),
                SyncEmbedding("g2", "two.jpg", "m", listOf(0f, 1f)),
            ),
        )

        val missing = style.embeddingsMissingFrom(mapOf("g1" to "one.jpg", "g2" to "two-old.jpg"))
        assertEquals(listOf("g2"), missing.map { it.garmentId })
        assertEquals(listOf(0f, 1f), missing.single().vector)
    }

    @Test
    fun `the blob is little-endian floats, so another platform reads the same numbers`() {
        val packed = StyleQueries.packVector(floatArrayOf(1f))
        // 1.0f is 0x3F800000: least significant byte first.
        assertContentEquals(byteArrayOf(0, 0, 0x80.toByte(), 0x3F), packed)
        assertContentEquals(floatArrayOf(1f), StyleQueries.unpackVector(packed))
    }
}
