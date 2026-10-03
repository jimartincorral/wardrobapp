package com.wardrobapp.server

import com.wardrobapp.api.ServerException
import com.wardrobapp.domain.GarmentImportException
import com.wardrobapp.domain.ImportFailureReason
import com.wardrobapp.domain.ImportedGarmentPreview
import com.wardrobapp.domain.UnsafeUrlException
import com.wardrobapp.domain.UnsafeUrlReason
import com.wardrobapp.presentation.GarmentImporter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A failure on the server arrives in the browser as the exception the screen
 * already handles, carrying the same reason.
 */
class FailureTest {

    /** An importer that fails the way it is told to, there being no internet in a test. */
    private class FailingImporter(private val failure: Exception) : GarmentImporter {
        override fun check(url: String) = url
        override suspend fun import(url: String): ImportedGarmentPreview = throw failure
    }

    @Test
    fun `an address the import refuses is refused in the browser for the same reason`() =
        serverTest(FailingImporter(UnsafeUrlException(UnsafeUrlReason.RedirectedToLocalHost("router.lan")))) {
            val refused = assertFailsWith<UnsafeUrlException> { importer.import("https://shop.example/") }
            assertEquals(UnsafeUrlReason.RedirectedToLocalHost("router.lan"), refused.reason)
        }

    @Test
    fun `a page with nothing to import says why in the browser too`() =
        serverTest(FailingImporter(GarmentImportException(ImportFailureReason.PageNotLoaded(404)))) {
            val failed = assertFailsWith<GarmentImportException> { importer.import("https://shop.example/") }
            assertEquals(ImportFailureReason.PageNotLoaded(404), failed.reason)
        }

    @Test
    fun `the server's own address checks run before anything is fetched`() = serverTest {
        // The real importer, :domain's checks and all. An address on the local
        // network is refused without a request being made, which is also why
        // this test needs no network.
        val refused = assertFailsWith<UnsafeUrlException> { importer.import("http://127.0.0.1/admin") }
        assertTrue(refused.reason is UnsafeUrlReason.HostIsLocal, refused.reason.toString())
    }

    @Test
    fun `anything else arrives as its message`() = serverTest(FailingImporter(IllegalStateException("The disk is full"))) {
        val failed = assertFailsWith<ServerException> { importer.import("https://shop.example/") }
        assertEquals("The disk is full", failed.message)
        assertEquals(500, failed.status)
    }

    @Test
    fun `a rating out of range is refused`() = serverTest {
        val garment = addGarment()
        outfitEdit.create(com.wardrobapp.presentation.OutfitDraft("x", listOf(garment.id), null, null))
        val outfit = outfits.saved(includeArchived = false).outfits.single()

        val refused = assertFailsWith<ServerException> { outfitDetail.rate(outfit.id, 9) }

        assertEquals(400, refused.status)
        assertEquals(null, outfitDetail.outfit(outfit.id)!!.rating)
    }

    @Test
    fun `a body that is not what the route takes is a bad request`() = serverTest {
        val refused = assertFailsWith<ServerException> {
            http.post("api/garments/search") {
                contentType(ContentType.Application.Json)
                setBody("""{"sort": "SIDEWAYS"}""")
            }
        }
        assertEquals(400, refused.status)
    }
}
