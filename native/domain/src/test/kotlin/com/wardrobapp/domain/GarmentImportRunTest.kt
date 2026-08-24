package com.wardrobapp.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What an import does with what it is given.
 *
 * The one part of URL import with no parity fixture behind it, because the
 * function it mirrors calls `fetch` and imports Expo's image service -- neither of
 * which the dump script can run. So these are written from the TypeScript
 * directly, and they cover the decisions rather than the plumbing: which
 * addresses are dialled, when the body is read at all, how many images are taken,
 * and what is said about the ones that were not.
 *
 * Every one of them is a case the app that ships cannot test, which is the point
 * of the network being an argument here.
 */
class GarmentImportRunTest {

    private val productPage = """
        <html><head>
        <meta property="og:title" content="Oxford Shirt" />
        <meta property="og:image" content="https://cdn.example.com/1.jpg" />
        </head></html>
    """.trimIndent()

    /** A page fetcher that records what it was asked for. */
    private class Recorder(
        private val page: String = "",
        private val status: Int = 200,
        private val contentType: String? = "text/html; charset=utf-8",
        private val declaredLength: Long? = null,
        private val finalUrl: String? = null,
    ) : PageFetcher {
        val requested = mutableListOf<String>()
        var bodyReads = 0

        override fun fetch(url: String): FetchedPage {
            requested += url
            return FetchedPage(
                finalUrl = finalUrl,
                status = status,
                contentType = contentType,
                declaredLength = declaredLength,
                readText = {
                    bodyReads++
                    page
                },
            )
        }
    }

    /** An image fetcher that succeeds for everything, recording the order. */
    private class Downloads(private val failFor: Set<String> = emptySet()) : ImageFetcher {
        val requested = mutableListOf<String>()

        override fun download(url: String): String {
            requested += url
            if (url in failFor) throw RuntimeException("no")
            return "file:///cache/${requested.size}.jpg"
        }
    }

    @Test
    fun `it fetches the normalized address, not the one it was handed`() {
        val pages = Recorder(productPage)

        importGarmentFromUrl("EXAMPLE.com/p?a=1#frag", pages, Downloads())

        // Scheme filled in, host lowered, fragment gone. What was typed is not
        // what should be dialled.
        assertEquals(listOf("https://example.com/p?a=1"), pages.requested)
    }

    @Test
    fun `an address on the local network is never fetched`() {
        val pages = Recorder(productPage)

        assertFailsWith<UnsafeUrlException> {
            importGarmentFromUrl("http://192.168.1.1/p", pages, Downloads())
        }

        assertEquals(emptyList(), pages.requested, "the request was made anyway")
    }

    @Test
    fun `a redirect onto the local network stops the body being read`() {
        val pages = Recorder(productPage, finalUrl = "http://169.254.169.254/latest/meta-data/")

        assertFailsWith<UnsafeUrlException> {
            importGarmentFromUrl("https://example.com/p", pages, Downloads())
        }

        // The request happened -- that is what a redirect means -- but nothing
        // came back out of it.
        assertEquals(1, pages.requested.size)
        assertEquals(0, pages.bodyReads, "the response was read after all")
    }

    @Test
    fun `a page that declares itself too large is not read`() {
        val pages = Recorder(productPage, declaredLength = MAX_PAGE_CHARS + 1L)

        val error = assertFailsWith<GarmentImportException> {
            importGarmentFromUrl("https://example.com/p", pages, Downloads())
        }

        assertEquals(ImportFailureReason.PageTooLarge, error.reason)
        assertEquals(0, pages.bodyReads, "it was read despite declaring its size")
    }

    @Test
    fun `a page too large without saying so is refused after reading`() {
        val pages = Recorder("x".repeat(MAX_PAGE_CHARS + 1))

        val error = assertFailsWith<GarmentImportException> {
            importGarmentFromUrl("https://example.com/p", pages, Downloads())
        }

        assertEquals(ImportFailureReason.PageTooLarge, error.reason)
    }

    @Test
    fun `a page exactly at the limit is read`() {
        // The bound is inclusive on both checks, and an off-by-one here would
        // refuse a page for being exactly the size it is allowed to be.
        val filler = "<!--" + "x".repeat(MAX_PAGE_CHARS - productPage.length - 7) + "-->"
        val page = productPage + filler
        assertEquals(MAX_PAGE_CHARS, page.length)

        val preview = importGarmentFromUrl(
            "https://example.com/p",
            Recorder(page, declaredLength = MAX_PAGE_CHARS.toLong()),
            Downloads(),
        )

        assertEquals(1, preview.downloadedImageUris.size)
    }

    @Test
    fun `a status outside the 2xx range names itself`() {
        val error = assertFailsWith<GarmentImportException> {
            importGarmentFromUrl("https://example.com/p", Recorder(productPage, status = 404), Downloads())
        }

        assertEquals(ImportFailureReason.PageNotLoaded(404), error.reason)
        assertEquals("Could not load page (404).", error.message)
    }

    @Test
    fun `a 204 is not a page even though it succeeded`() {
        // Faithful to `response.ok`, which is 200-299 -- so a 204 passes the
        // status check and then finds no images, rather than being refused for
        // its status.
        val error = assertFailsWith<GarmentImportException> {
            importGarmentFromUrl("https://example.com/p", Recorder("", status = 204), Downloads())
        }

        assertEquals(ImportFailureReason.NoImagesFound, error.reason)
    }

    @Test
    fun `something that is not a web page is refused on its content type`() {
        val error = assertFailsWith<GarmentImportException> {
            importGarmentFromUrl(
                "https://example.com/p.pdf",
                Recorder(productPage, contentType = "application/pdf"),
                Downloads(),
            )
        }

        assertEquals(ImportFailureReason.NotAWebPage, error.reason)
    }

    @Test
    fun `a server that sends no content type at all is given the benefit of the doubt`() {
        val preview = importGarmentFromUrl(
            "https://example.com/p",
            Recorder(productPage, contentType = null),
            Downloads(),
        )

        assertEquals("Oxford Shirt", preview.title)
    }

    @Test
    fun `only the first eight images are taken, and the rest are reported`() {
        val page = buildString {
            append("<html><body>")
            for (index in 1..12) append("""<img src="https://cdn.example.com/$index.jpg" />""")
            append("</body></html>")
        }
        val downloads = Downloads()

        val preview = importGarmentFromUrl("https://example.com/p", Recorder(page), downloads)

        assertEquals(MAX_IMPORTED_IMAGES, preview.imageUrls.size)
        assertEquals(MAX_IMPORTED_IMAGES, downloads.requested.size)
        // In the order the page listed them: the first becomes the garment's photo.
        assertEquals("https://cdn.example.com/1.jpg", downloads.requested.first())
        assertTrue(ImportWarning.ImagesCapped(listed = 12, used = 8) in preview.warnings)
    }

    @Test
    fun `an image pointing at the local network is dropped and counted`() {
        val page = """
            <html><body>
            <img src="https://cdn.example.com/ok.jpg" />
            <img src="http://192.168.1.1/cam.jpg" />
            <img src="http://printer.local/scan.png" />
            </body></html>
        """.trimIndent()
        val downloads = Downloads()

        val preview = importGarmentFromUrl("https://example.com/p", Recorder(page), downloads)

        assertEquals(listOf("https://cdn.example.com/ok.jpg"), downloads.requested)
        assertTrue(ImportWarning.ImagesBlocked(2) in preview.warnings)
    }

    @Test
    fun `a page whose every image is local is refused rather than half-imported`() {
        val page = """<html><body><img src="http://10.0.0.5/a.jpg" /></body></html>"""

        val error = assertFailsWith<GarmentImportException> {
            importGarmentFromUrl("https://example.com/p", Recorder(page), Downloads())
        }

        assertEquals(ImportFailureReason.NoFetchableImages, error.reason)
    }

    @Test
    fun `a download that fails costs one photo, not the import`() {
        val page = """
            <html><body>
            <img src="https://cdn.example.com/a.jpg" />
            <img src="https://cdn.example.com/b.jpg" />
            </body></html>
        """.trimIndent()

        val preview = importGarmentFromUrl(
            "https://example.com/p",
            Recorder(page),
            Downloads(failFor = setOf("https://cdn.example.com/a.jpg")),
        )

        assertEquals(1, preview.downloadedImageUris.size)
        assertTrue(ImportWarning.ImagesFailed(1) in preview.warnings)
        // Both are still listed: what the page offered is not the same question
        // as what arrived.
        assertEquals(2, preview.imageUrls.size)
    }

    @Test
    fun `nothing arriving at all is a failure, not an empty garment`() {
        val page = """<html><body><img src="https://cdn.example.com/a.jpg" /></body></html>"""

        val error = assertFailsWith<GarmentImportException> {
            importGarmentFromUrl(
                "https://example.com/p",
                Recorder(page),
                Downloads(failFor = setOf("https://cdn.example.com/a.jpg")),
            )
        }

        assertEquals(ImportFailureReason.NoImagesDownloaded, error.reason)
    }

    @Test
    fun `a warning from the page itself is kept alongside the ones counted here`() {
        val page = """
            <html><head>
            <script type="application/ld+json">{ not json </script>
            <meta property="og:image" content="https://cdn.example.com/a.jpg" />
            </head><body><img src="http://10.0.0.1/b.jpg" /></body></html>
        """.trimIndent()

        val preview = importGarmentFromUrl("https://example.com/p", Recorder(page), Downloads())

        assertEquals(
            listOf(
                ImportWarning.StructuredDataUnreadable,
                ImportWarning.ImagesBlocked(1),
            ),
            preview.warnings,
        )
    }

    @Test
    fun `the preview carries what the form needs to fill itself in`() {
        val page = """
            <html><head>
            <script type="application/ld+json">
            {"@type":"Product","name":"Poplin Shirt","brand":{"name":"Zara"},
             "image":"https://static.zara.com/a.jpg"}
            </script>
            </head></html>
        """.trimIndent()

        val preview = importGarmentFromUrl("https://www.zara.com/uk/p.html", Recorder(page), Downloads())

        assertEquals("https://www.zara.com/uk/p.html", preview.sourceUrl)
        assertEquals("Poplin Shirt", preview.title)
        assertEquals("Zara", preview.brand)
        assertEquals(ImportParser.JSON_LD, preview.parser)
        assertEquals(listOf("file:///cache/1.jpg"), preview.downloadedImageUris)
    }

    @Test
    fun `every failure says something different`() {
        // The reasons exist to be told apart by a screen, and two of them
        // sharing a sentence would make one of them pointless.
        val messages = listOf(
            ImportFailureReason.PageTimedOut,
            ImportFailureReason.PageTooLarge,
            ImportFailureReason.PageNotLoaded(500),
            ImportFailureReason.NotAWebPage,
            ImportFailureReason.NoImagesFound,
            ImportFailureReason.NoFetchableImages,
            ImportFailureReason.NoImagesDownloaded,
        ).map { it.englishMessage() }

        assertEquals(messages.size, messages.toSet().size, "two failures read the same")
        assertTrue(messages.none { it.isBlank() })
    }
}
