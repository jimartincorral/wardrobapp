package com.wardrobapp.server

import com.wardrobapp.api.HttpBulkAddSource
import com.wardrobapp.api.HttpGarmentDetailSource
import com.wardrobapp.api.HttpGarmentFormSource
import com.wardrobapp.api.HttpGarmentImporter
import com.wardrobapp.api.HttpHomeSource
import com.wardrobapp.api.HttpOutfitDetailSource
import com.wardrobapp.api.HttpOutfitEditSource
import com.wardrobapp.api.HttpOutfitsSource
import com.wardrobapp.api.HttpPhotos
import com.wardrobapp.api.HttpStatisticsSource
import com.wardrobapp.api.HttpStorageSource
import com.wardrobapp.api.HttpWardrobeSource
import com.wardrobapp.api.speakWardrobe
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.presentation.GarmentFormState
import com.wardrobapp.presentation.GarmentImporter
import com.wardrobapp.presentation.WardrobeQuery
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files

/**
 * A server over a real database in a temporary directory, and the browser's
 * sources pointed at it.
 *
 * The tests in this module drive the server through :api's Http sources rather
 * than with hand-written requests, so each one tests both halves at once: that
 * the routes the client asks for are the ones the server answers, with bodies
 * both sides read the same way. A test that wrote its own requests would test
 * the server against the test's idea of the client.
 */
class ServerUnderTest(val http: HttpClient, val wardrobe: ServerWardrobe, val dataDirectory: File) {
    val home = HttpHomeSource(http)
    val outfitDetail = HttpOutfitDetailSource(http)
    val outfitEdit = HttpOutfitEditSource(http)
    val wardrobeList = HttpWardrobeSource(http)
    val statistics = HttpStatisticsSource(http)
    val outfits = HttpOutfitsSource(http)
    val garmentDetail = HttpGarmentDetailSource(http)
    val bulkAdd = HttpBulkAddSource(http)
    val garmentForm = HttpGarmentFormSource(http)
    val importer = HttpGarmentImporter(http)
    val storage = HttpStorageSource(http)
    val photos = HttpPhotos(http)

    val photoDirectory: File get() = wardrobe.photos.directory

    /** Upload a photo, as the browser will before saving a garment. */
    suspend fun uploadPhoto(bytes: ByteArray = jpeg()): String = photos.upload(bytes, ContentType.Image.JPEG)

    /** Add a garment through the form, the way a person would; the garment as the wardrobe lists it. */
    suspend fun addGarment(
        category: String = "tops",
        colour: String = "#112233",
        brand: String = "",
        subcategory: String? = null,
        photo: String? = null,
    ): GarmentRecord {
        val before = everything().map { it.id }.toSet()
        garmentForm.save(
            garmentId = null,
            form = GarmentFormState(
                imageUris = listOf(photo ?: uploadPhoto()),
                bgRemovedUris = listOf(""),
                category = category,
                subcategories = listOfNotNull(subcategory),
                brand = brand,
                colorPalette = listOf(colour),
                colorsChosen = true,
            ),
            previouslyStored = emptyList(),
        )
        return everything().single { it.id !in before }
    }

    suspend fun everything(): List<GarmentRecord> = wardrobeList.garments(WardrobeQuery(includeRetired = true))
}

fun serverTest(
    importer: GarmentImporter? = null,
    settings: ServerSettings = ServerSettings(),
    block: suspend ServerUnderTest.() -> Unit,
) {
    val directory = Files.createTempDirectory("wardrobe-server").toFile()
    val wardrobe = ServerWardrobe(directory, importer)
    try {
        testApplication {
            application { wardrobeApi(wardrobe, settings) }
            // The base the browser will have, so the routes are resolved the
            // way they will be there: relative to the page.
            val http = createClient { speakWardrobe("http://localhost/") }
            ServerUnderTest(http, wardrobe, directory).block()
        }
    } finally {
        wardrobe.close()
        directory.deleteRecursively()
    }
}

/** Enough of a JPEG to be taken for one: its signature, then anything. */
fun jpeg(seed: Int = 0): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(64) { (it + seed).toByte() }

fun png(): ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(32)

fun webp(): ByteArray = "RIFF".encodeToByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".encodeToByteArray() + ByteArray(32)
