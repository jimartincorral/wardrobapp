package com.wardrobapp.server

import com.wardrobapp.data.JdbcSqlDriver
import com.wardrobapp.data.toStoredImageRef
import com.wardrobapp.data.wardrobeFilesIn
import com.wardrobapp.domain.DuplicateCandidate
import com.wardrobapp.presentation.BackgroundEdit
import com.wardrobapp.presentation.BulkAddState
import com.wardrobapp.presentation.GarmentFormState
import com.wardrobapp.presentation.HomeCounts
import com.wardrobapp.presentation.WardrobeQuery
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GarmentRoutesTest {

    @Test
    fun `an empty wardrobe counts nothing`() = serverTest {
        assertEquals(HomeCounts(items = 0, archived = 0, rated = 0), home.counts())
        assertEquals(emptyList(), everything())
    }

    @Test
    fun `a saved garment comes back with photos the page can load`() = serverTest {
        val bytes = jpeg(seed = 7)
        val photo = uploadPhoto(bytes)

        val garment = addGarment(photo = photo, brand = "Acme")

        // The reference is relative to the page -- which is where the server
        // answers it -- not a path on the server's disk.
        assertEquals(photo, garment.imageUri)
        assertTrue(photo.startsWith("p/main/photos/"), photo)
        assertEquals(listOf("Acme"), garmentForm.brands())
        assertEquals(HomeCounts(items = 1, archived = 0, rated = 0), home.counts())

        val served = fromPage(photo)
        assertEquals(ContentType.Image.JPEG, served.contentType())
        assertContentEquals(bytes, served.readRawBytes())
    }

    @Test
    fun `the row holds the photo's name, as the phone's do`() = serverTest {
        // So a wardrobe moved between the two reads the same: a row that held
        // `photos/x.jpg` would be a garment with a broken photo on the phone.
        val garment = addGarment()

        val row = JdbcSqlDriver.open(wardrobeFilesIn(dataDirectory).databaseFile).use { database ->
            database.query("SELECT image_uri, image_uris FROM garments WHERE id = ?", listOf(garment.id)).single()
        }
        val name = garment.imageUri.substringAfterLast('/')
        assertEquals(name, row["image_uri"])
        assertEquals("[\"$name\"]", row["image_uris"])
        assertTrue(File(photoDirectory, name).isFile)
    }

    @Test
    fun `editing a garment lets go of the photo it stopped using`() = serverTest {
        val first = uploadPhoto(jpeg(seed = 1))
        val garment = addGarment(photo = first)
        val second = uploadPhoto(jpeg(seed = 2))

        garmentForm.save(
            garmentId = garment.id,
            form = GarmentFormState(
                imageUris = listOf(second),
                bgRemovedUris = listOf(""),
                category = "tops",
                colorPalette = listOf("#112233"),
                colorsChosen = true,
            ),
            previouslyStored = listOf(first),
        )

        assertEquals(listOf(second), garmentForm.garment(garment.id)!!.imageUris)
        assertFalse(File(photoDirectory, toStoredImageRef(first)).exists())
        assertTrue(File(photoDirectory, toStoredImageRef(second)).isFile)
    }

    @Test
    fun `a garment retired and then deleted takes its photo with it`() = serverTest {
        val garment = addGarment()

        garmentDetail.setInUse(garment.id, false)
        assertEquals(HomeCounts(items = 0, archived = 1, rated = 0), home.counts())
        assertEquals(emptyList(), wardrobeList.garments(WardrobeQuery()))
        assertEquals(listOf(garment.id), wardrobeList.garments(WardrobeQuery(includeRetired = true)).map { it.id })

        garmentDetail.delete(garment.id)
        assertNull(garmentDetail.garment(garment.id))
        assertFalse(File(photoDirectory, toStoredImageRef(garment.imageUri)).exists())
    }

    @Test
    fun `a missing garment is null rather than a failure`() = serverTest {
        assertNull(garmentDetail.garment("no-such-garment"))
        assertNull(garmentForm.garment("no-such-garment"))
        assertNull(outfits.garment("no-such-garment"))
    }

    @Test
    fun `the wardrobe list is filtered on the server as on the phone`() = serverTest {
        addGarment(category = "tops")
        val trousers = addGarment(category = "bottoms")

        val query = WardrobeQuery(category = "bottoms")
        assertEquals(listOf(trousers.id), wardrobeList.garments(query).map { it.id })
        assertEquals(wardrobe.wardrobe.garments(query), wardrobeList.garments(query))
    }

    @Test
    fun `background edits are saved to the garment`() = serverTest {
        val garment = addGarment()
        val cutout = uploadPhoto(png())

        garmentDetail.savePhotos(
            garment.id,
            BackgroundEdit(images = garment.imageUris, cutouts = listOf(cutout), discardable = null),
            alsoImages = false,
        )

        assertEquals(listOf(cutout), garmentDetail.garment(garment.id)!!.imageUrisNoBg)
    }

    @Test
    fun `bulk add stores a garment`() = serverTest {
        val photo = uploadPhoto()

        bulkAdd.save(BulkAddState.Draft(imageUri = photo, category = "shoes", colorPalette = listOf("#000000")))

        assertEquals(listOf("shoes"), everything().map { it.category })
    }

    @Test
    fun `the form finds a garment it would duplicate`() = serverTest {
        val existing = addGarment(category = "tops", subcategory = "shirt", colour = "#112233")
        addGarment(category = "tops", subcategory = "jumper", colour = "#112233")

        val matches = garmentForm.duplicatesOf(
            DuplicateCandidate(category = "tops", subcategories = listOf("shirt"), colorPrimary = "#112233"),
        )

        assertEquals(listOf(existing.id), matches.map { it.garment.id })
    }

    @Test
    fun `storage counts garments and the bytes their photos take`() = serverTest {
        val photo = jpeg()
        addGarment(photo = uploadPhoto(photo))

        val figures = storage.storage()

        assertEquals(1, figures.garments)
        assertEquals(photo.size.toLong(), figures.photoBytes)
    }
}
