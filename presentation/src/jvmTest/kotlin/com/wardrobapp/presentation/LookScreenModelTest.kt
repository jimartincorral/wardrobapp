package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.InspirationRecord
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LookScreenModelTest {

    private class FakeSource : LookSource {
        var look: InspirationRecord? = InspirationRecord("l1", "look.jpg", "2026-01-01T00:00:00.000Z")
        var kept: List<LookPiece>? = null
        var answer: List<LookPiece>? = listOf(
            LookPiece("tops", "Shirt", "#FFFFFF"),
            LookPiece("bottoms", "Jeans", "#000080"),
            LookPiece("shoes", "Loafers", "#8B4513"),
        )
        var reads = 0
        val saved = mutableListOf<Pair<String, List<String>>>()
        var wardrobe: List<GarmentRecord> = listOf(
            testGarment("shirt", "tops", "Shirt").copy(colorPrimary = "#FFFFFF", colorPalette = listOf("#FFFFFF")),
            testGarment("jeans", "bottoms", "Jeans").copy(colorPrimary = "#000080", colorPalette = listOf("#000080")),
        )

        override suspend fun look(id: String) = look?.takeIf { it.id == id }
        override suspend fun storedPieces(id: String) = kept
        override suspend fun read(look: InspirationRecord): List<LookPiece>? {
            reads++
            return answer
        }
        override suspend fun keepPieces(id: String, pieces: List<LookPiece>) {
            kept = pieces
        }
        override suspend fun wardrobe() = wardrobe
        override suspend fun vectors(id: String) = null to emptyMap<String, FloatArray>()
        override suspend fun saveOutfit(name: String, garmentIds: List<String>): String {
            saved += name to garmentIds
            return "o1"
        }

        var reader = LookReader.READY
        var afterDownload = LookReader.READY
        var downloads = 0
        override suspend fun reader() = reader
        override suspend fun downloadReader(): LookReader {
            downloads++
            reader = afterDownload
            return reader
        }
    }

    @Test
    fun `a look is read once, kept, and rebuilt from the wardrobe`() = runTest {
        val source = FakeSource()
        val first = LookScreenModel(this, source, "l1")
        advanceUntilIdle()

        val rows = first.state.value.rows
        assertEquals(listOf("shirt", "jeans", null), rows.map { it.garment?.id })
        assertEquals(source.answer, source.kept)
        assertTrue(first.state.value.canSave)

        LookScreenModel(this, source, "l1")
        advanceUntilIdle()
        assertEquals(1, source.reads, "the kept reading was not used")
    }

    @Test
    fun `a look read as nothing says so, and reading again asks again`() = runTest {
        val source = FakeSource().apply { answer = null }
        val model = LookScreenModel(this, source, "l1")
        advanceUntilIdle()
        assertTrue(model.state.value.unread)
        assertFalse(model.state.value.canSave)

        source.answer = listOf(LookPiece("tops", "Shirt", "#FFFFFF"))
        source.kept = listOf(LookPiece("shoes", "Boots", null))
        model.onRetry()
        advanceUntilIdle()
        assertFalse(model.state.value.unread)
        assertEquals("tops", model.state.value.rows.single().piece.category, "a retry reads the photo, not what was kept")
    }

    @Test
    fun `what the wardrobe has of the look is saved as an outfit named like any other`() = runTest {
        val source = FakeSource()
        val model = LookScreenModel(this, source, "l1")
        advanceUntilIdle()

        model.onSaveRequested()
        advanceUntilIdle()

        assertEquals(listOf("Shirt + Jeans" to listOf("shirt", "jeans")), source.saved)
        assertEquals("o1", model.state.value.savedOutfitId)
        model.onSavedOutfitOpened()
        assertNull(model.state.value.savedOutfitId)
    }

    @Test
    fun `a look that is gone says so`() = runTest {
        val source = FakeSource().apply { look = null }
        val model = LookScreenModel(this, source, "l1")
        advanceUntilIdle()
        assertTrue(model.state.value.missing)
    }

    @Test
    fun `a look the phone cannot read yet says why, and reads once the reader is downloaded`() = runTest {
        val source = FakeSource().apply { reader = LookReader.DOWNLOADABLE }
        val model = LookScreenModel(this, source, "l1")
        advanceUntilIdle()

        assertEquals(LookReader.DOWNLOADABLE, model.state.value.reader)
        assertEquals(0, source.reads, "a photo was put to a reader that is not there")
        assertFalse(model.state.value.unread, "no reader is not the same as a look read as nothing")

        model.onDownloadReader()
        advanceUntilIdle()

        assertEquals(1, source.downloads)
        assertEquals(LookReader.READY, model.state.value.reader)
        assertEquals(listOf("shirt", "jeans", null), model.state.value.rows.map { it.garment?.id })
    }

    @Test
    fun `a phone without the reader says so, but a look read before still opens`() = runTest {
        val source = FakeSource().apply { reader = LookReader.UNAVAILABLE }
        val unread = LookScreenModel(this, source, "l1")
        advanceUntilIdle()
        assertEquals(LookReader.UNAVAILABLE, unread.state.value.reader)

        source.kept = listOf(LookPiece("tops", "Shirt", "#FFFFFF"))
        val kept = LookScreenModel(this, source, "l1")
        advanceUntilIdle()
        assertEquals(LookReader.READY, kept.state.value.reader)
        assertEquals("shirt", kept.state.value.rows.single().garment?.id)
    }

    @Test
    fun `a download that does not finish leaves the screen saying where it stands`() = runTest {
        val source = FakeSource().apply {
            reader = LookReader.DOWNLOADABLE
            afterDownload = LookReader.DOWNLOADING
        }
        val model = LookScreenModel(this, source, "l1")
        advanceUntilIdle()

        model.onDownloadReader()
        advanceUntilIdle()

        assertEquals(LookReader.DOWNLOADING, model.state.value.reader)
        assertEquals(0, source.reads)
    }

    @Test
    fun `a photo of what was worn is read, matched, and kept only in memory`() = runTest {
        val wardrobe = FakeSource().apply { look = null }
        val source = WornPhotoSource(wardrobe, "selfie.jpg")
        val model = LookScreenModel(this, source, WORN_LOOK_ID)
        advanceUntilIdle()

        assertEquals("selfie.jpg", model.state.value.look?.imageUri)
        assertEquals(listOf("shirt", "jeans", null), model.state.value.rows.map { it.garment?.id })
        assertNull(wardrobe.kept, "a worn photo's reading went into the database's keeping")

        model.onSaveRequested()
        advanceUntilIdle()
        assertEquals(listOf("shirt", "jeans"), wardrobe.saved.single().second)

        LookScreenModel(this, source, WORN_LOOK_ID)
        advanceUntilIdle()
        assertEquals(1, wardrobe.reads, "the same photo, opened again, was read again")
    }
}
