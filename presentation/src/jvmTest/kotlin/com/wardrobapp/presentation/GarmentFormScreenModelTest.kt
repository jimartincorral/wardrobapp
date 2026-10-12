package com.wardrobapp.presentation

import com.wardrobapp.data.DuplicateGarment
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.domain.DuplicateCandidate
import com.wardrobapp.domain.GarmentReading
import com.wardrobapp.domain.ReadType
import com.wardrobapp.domain.GarmentImportException
import com.wardrobapp.domain.ImportFailureReason
import com.wardrobapp.domain.ImportParser
import com.wardrobapp.domain.ImportedGarmentPreview
import com.wardrobapp.domain.PhantomGarment
import com.wardrobapp.domain.UnsafeUrlException
import com.wardrobapp.domain.UnsafeUrlReason
import com.wardrobapp.presentation.GarmentFormScreenState.ImportProblem
import java.io.IOException
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GarmentFormScreenModelTest {

    private class FakePhotos : PhotoWork<String> {
        val deleted = mutableListOf<String>()
        var cutGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun store(photo: String) = "stored/$photo"
        override suspend fun delete(photo: String) {
            deleted += photo
        }
        override suspend fun colors(photo: String) = listOf("#AA0000")
        override suspend fun cutOut(photo: String): String {
            cutGate?.await()
            return "$photo.nobg"
        }

        /** What each photo reads as; a photo not here reads as nothing. */
        val readings = mutableMapOf<String, GarmentReading>()
        var readGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val read = mutableListOf<String>()
        override suspend fun read(photo: String): GarmentReading? {
            read += photo
            readGate?.await()
            return readings[photo]
        }
    }

    private class FakeSource : GarmentFormSource {
        var record: GarmentRecord? = testGarment("g1").copy(imageUri = "old.jpg", imageUris = listOf("old.jpg"), brand = "Acne")
        var matches = emptyList<DuplicateGarment>()
        val saves = mutableListOf<Triple<String?, GarmentFormState, List<String>>>()

        override suspend fun garment(id: String) = record?.takeIf { it.id == id }
        override suspend fun brands() = listOf("Acne", "Arket")
        override suspend fun duplicatesOf(candidate: DuplicateCandidate) = matches
        override suspend fun save(garmentId: String?, form: GarmentFormState, previouslyStored: List<String>) {
            saves += Triple(garmentId, form, previouslyStored)
        }
    }

    private class FakeImporter : GarmentImporter {
        var failure: Exception? = null
        override fun check(url: String): String {
            if ("192.168." in url) throw UnsafeUrlException(UnsafeUrlReason.HostIsLocal("192.168.1.1"))
            return url.trim()
        }
        override suspend fun import(url: String): ImportedGarmentPreview {
            failure?.let { throw it }
            return ImportedGarmentPreview(
                sourceUrl = url,
                title = "Jacket",
                brand = "Arket",
                imageUrls = listOf("https://shop.example/1.jpg"),
                downloadedImageUris = listOf("stored/import-1.jpg"),
                warnings = emptyList(),
                parser = ImportParser.entries.first(),
            )
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.model(
        source: FakeSource = FakeSource(),
        photos: FakePhotos = FakePhotos(),
        importer: FakeImporter = FakeImporter(),
        garmentId: String? = null,
        wanted: PhantomGarment? = null,
    ) = GarmentFormScreenModel(this, photos, source, importer, garmentId, wanted)

    @Test
    fun `adding starts from what a gap suggested`() = runTest {
        val form = model(wanted = PhantomGarment(category = "bottoms", subcategory = "Jeans", colorPrimary = "#000080"))
        advanceUntilIdle()

        assertEquals("bottoms", form.state.value.form.category)
        assertEquals(listOf("Jeans"), form.state.value.form.subcategories)
        assertEquals(listOf("Acne", "Arket"), form.state.value.brands)
    }

    @Test
    fun `editing starts from the garment as stored, with its colours treated as chosen`() = runTest {
        val form = model(garmentId = "g1")
        advanceUntilIdle()

        assertTrue(form.isEditing)
        assertEquals("Acne", form.state.value.form.brand)
        assertTrue(form.state.value.form.colorsChosen)
    }

    @Test
    fun `a picked photo is stored, shown, and its colours read`() = runTest {
        val form = model()
        advanceUntilIdle()

        form.onPhotoPicked("camera.jpg")
        advanceUntilIdle()

        assertEquals(listOf("stored/camera.jpg"), form.state.value.form.imageUris)
        assertEquals("#AA0000", form.state.value.form.colorPalette.first())
    }

    @Test
    fun `removing a photo this form added deletes it, but not one the garment owns`() = runTest {
        val photos = FakePhotos()
        val form = model(photos = photos, garmentId = "g1")
        advanceUntilIdle()
        form.onPhotoPicked("new.jpg")
        advanceUntilIdle()

        form.onPhotoRemoved(form.state.value.form.imageUris.indexOf("stored/new.jpg"))
        form.onPhotoRemoved(form.state.value.form.imageUris.indexOf("old.jpg"))
        advanceUntilIdle()

        assertEquals(listOf("stored/new.jpg"), photos.deleted, "the stored garment's photo is the row's until a save")
    }

    @Test
    fun `a cut-out lands on its own photo even if another was selected meanwhile`() = runTest {
        val photos = FakePhotos().apply { cutGate = kotlinx.coroutines.CompletableDeferred() }
        val form = model(photos = photos)
        advanceUntilIdle()
        form.onPhotoPicked("a.jpg")
        form.onPhotoPicked("b.jpg")
        advanceUntilIdle()
        form.onPhotoSelected(0)

        form.onRemoveBackground()
        advanceUntilIdle()
        form.onPhotoSelected(1)
        photos.cutGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("stored/a.jpg.nobg", ""), form.state.value.form.bgRemovedUris)
    }

    @Test
    fun `a cut-out of a photo removed meanwhile is thrown away`() = runTest {
        val photos = FakePhotos().apply { cutGate = kotlinx.coroutines.CompletableDeferred() }
        val form = model(photos = photos)
        advanceUntilIdle()
        form.onPhotoPicked("a.jpg")
        form.onPhotoPicked("b.jpg")
        advanceUntilIdle()
        form.onPhotoSelected(0)

        form.onRemoveBackground()
        advanceUntilIdle()
        form.onPhotoRemoved(0)
        photos.cutGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(""), form.state.value.form.bgRemovedUris)
        assertTrue("stored/a.jpg.nobg" in photos.deleted)
        assertEquals(false, form.state.value.removingBackground)
    }

    @Test
    fun `saving with no photo asks for one`() = runTest {
        val source = FakeSource()
        val form = model(source = source)
        advanceUntilIdle()

        form.onSaveRequested()

        assertEquals(ErrorFallback.PHOTO_REQUIRED, form.state.value.errorFallback)
        assertEquals(emptyList(), source.saves)
    }

    @Test
    fun `a likely duplicate is shown once, and saving again means it`() = runTest {
        val source = FakeSource().apply { matches = listOf(DuplicateGarment(testGarment("twin"))) }
        val form = model(source = source)
        advanceUntilIdle()
        form.onPhotoPicked("a.jpg")
        advanceUntilIdle()

        form.onSaveRequested()
        advanceUntilIdle()
        assertEquals(listOf("twin"), form.state.value.duplicates.map { it.garment.id })
        assertEquals(emptyList(), source.saves)

        form.onSaveRequested(force = true)
        advanceUntilIdle()
        assertEquals(null, source.saves.single().first)
        assertTrue(form.state.value.saved)
    }

    @Test
    fun `an edit saves over the garment and hands over what it referenced`() = runTest {
        val source = FakeSource().apply { matches = listOf(DuplicateGarment(testGarment("twin"))) }
        val form = model(source = source, garmentId = "g1")
        advanceUntilIdle()

        form.onSaveRequested()
        advanceUntilIdle()

        val (id, _, previous) = source.saves.single()
        assertEquals("g1", id, "an edit is never checked for duplicates")
        assertEquals(listOf("old.jpg", ""), previous)
    }

    @Test
    fun `a shared link on the local network is refused without asking`() = runTest {
        val form = model()
        advanceUntilIdle()

        form.onSharedLinkReceived("http://192.168.1.1/admin")

        assertEquals(ImportProblem.Unsafe(UnsafeUrlReason.HostIsLocal("192.168.1.1")), form.state.value.urlImport.problem)
        assertNull(form.state.value.urlImport.awaitingConfirmation)
    }

    @Test
    fun `a shared link is confirmed before it is fetched, then fills the form in`() = runTest {
        val form = model()
        advanceUntilIdle()

        form.onSharedLinkReceived(" https://shop.example/jacket ")
        assertEquals("https://shop.example/jacket", form.state.value.urlImport.awaitingConfirmation)

        form.onSharedLinkConfirmed()
        advanceUntilIdle()

        assertEquals(listOf("stored/import-1.jpg"), form.state.value.form.imageUris)
        assertEquals("Arket", form.state.value.form.brand)
        assertEquals(1, form.state.value.urlImport.imported)
    }

    @Test
    fun `an import failure is reported by its kind`() = runTest {
        val importer = FakeImporter()
        val form = model(importer = importer)
        advanceUntilIdle()
        form.onImportUrlChanged("https://shop.example/x")

        importer.failure = GarmentImportException(ImportFailureReason.NoImagesFound)
        form.onImportRequested()
        advanceUntilIdle()
        assertEquals(ImportProblem.Failed(ImportFailureReason.NoImagesFound), form.state.value.urlImport.problem)

        importer.failure = UnsafeUrlException(UnsafeUrlReason.RedirectedToLocalHost("10.0.0.1"))
        form.onImportRequested()
        advanceUntilIdle()
        assertEquals(ImportProblem.Unsafe(UnsafeUrlReason.RedirectedToLocalHost("10.0.0.1")), form.state.value.urlImport.problem)

        importer.failure = IOException("connection refused")
        form.onImportRequested()
        advanceUntilIdle()
        assertEquals(ImportProblem.Foreign("connection refused"), form.state.value.urlImport.problem)
    }

    // ---- from GarmentFormErrorDismissalTest, which ran under Robolectric in :app ----
    //
    // Closing an error dialog has to actually close it. Most errors on this screen
    // carry no message of their own and are shown through errorFallback, a reason
    // rather than a string. onErrorDismissed cleared only `error`, so the moment
    // the dialog closed, errorText() fell back to the still-set errorFallback and
    // drew the same dialog again -- immediately, since nothing else changed. An
    // AlertDialog is modal, so this read as the close button doing nothing.

    @Test
    fun `dismissing an error clears both the message and its fallback`() = runTest {
        val form = model()

        // Saving with no photo attached is the simplest path to a fallback-only
        // error -- no camera, no background model, nothing to fake.
        form.onSaveRequested()
        form.onErrorDismissed()

        assertNull(form.state.value.error, "the message survived being dismissed")
        assertNull(form.state.value.errorFallback, "the fallback survived being dismissed, which is what reopened the dialog")
    }

    @Test
    fun `an error after a dismissed one is not titled with the first one's title`() = runTest {
        val form = model()

        // A photo failure, titled as one...
        form.onCameraUnavailable()
        assertEquals(ErrorTitle.PHOTO, form.state.value.errorTitle)
        form.onErrorDismissed()

        // ...and then a save with no photo, which sets no title of its own and
        // used to arrive under "Couldn't use that photo".
        form.onSaveRequested()

        assertEquals(ErrorTitle.SAVE, form.state.value.errorTitle)
    }

    @Test
    fun `a picked photo is read for its type, and its cut-out read again`() = runTest {
        val photos = FakePhotos().apply {
            readings["stored/a.jpg"] = GarmentReading(type = ReadType("tops", null))
            readings["stored/a.jpg.nobg"] = GarmentReading(type = ReadType("tops", "Polo"), brand = "Lacoste")
        }
        val form = model(photos = photos)
        advanceUntilIdle()

        form.onPhotoPicked("a.jpg")
        advanceUntilIdle()
        assertEquals("tops", form.state.value.form.category)
        assertEquals(emptyList(), form.state.value.form.subcategories)

        form.onRemoveBackground()
        advanceUntilIdle()
        assertEquals(listOf("Polo"), form.state.value.form.subcategories)
        assertEquals("Lacoste", form.state.value.form.brand)
        assertTrue(form.state.value.form.typeRead)
        assertEquals(false, form.state.value.readingPhoto)
    }

    @Test
    fun `a reading of a photo no longer on show is dropped`() = runTest {
        val photos = FakePhotos().apply {
            readGate = kotlinx.coroutines.CompletableDeferred()
            readings["stored/a.jpg"] = GarmentReading(type = ReadType("shoes", "Boots"))
        }
        val form = model(photos = photos)
        advanceUntilIdle()

        form.onPhotoPicked("a.jpg")
        advanceUntilIdle()
        assertTrue(form.state.value.readingPhoto)
        form.onPhotoPicked("b.jpg")
        advanceUntilIdle()
        photos.readGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals("tops", form.state.value.form.category, "boots were read off a photo nobody is looking at")
        assertEquals(false, form.state.value.readingPhoto)
    }

    @Test
    fun `a type tapped before the reading lands is kept, and an edit is never read`() = runTest {
        val photos = FakePhotos().apply {
            readGate = kotlinx.coroutines.CompletableDeferred()
            readings["stored/a.jpg"] = GarmentReading(type = ReadType("shoes", "Boots"))
            readings["stored/new.jpg"] = GarmentReading(type = ReadType("shoes", "Boots"))
        }
        val adding = model(photos = photos)
        advanceUntilIdle()
        adding.onPhotoPicked("a.jpg")
        advanceUntilIdle()
        adding.onCategorySelected("bottoms")
        photos.readGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("bottoms", adding.state.value.form.category)

        photos.read.clear()
        val editing = model(photos = photos, garmentId = "g1")
        advanceUntilIdle()
        editing.onPhotoPicked("new.jpg")
        advanceUntilIdle()
        assertEquals(emptyList(), photos.read)
    }

    @Test
    fun `an import is read from its page first, and its photo only fills what the page left open`() = runTest {
        val photos = FakePhotos().apply {
            readings["stored/import-1.jpg"] = GarmentReading(
                type = ReadType("outerwear", "Coat"),
                attributes = com.wardrobapp.domain.GarmentAttributes(pattern = com.wardrobapp.domain.Pattern.SOLID),
            )
        }
        val form = model(photos = photos)
        advanceUntilIdle()

        // FakeImporter's page is titled "Jacket"; the default reading of its
        // words finds that, and the photo's "Coat" does not replace it.
        form.onImportUrlChanged("https://shop.example/jacket")
        form.onImportRequested()
        advanceUntilIdle()

        assertEquals("outerwear", form.state.value.form.category)
        assertEquals(listOf("Jacket"), form.state.value.form.subcategories)
        assertEquals(com.wardrobapp.domain.Pattern.SOLID, form.state.value.form.attributes.pattern)
    }
}
