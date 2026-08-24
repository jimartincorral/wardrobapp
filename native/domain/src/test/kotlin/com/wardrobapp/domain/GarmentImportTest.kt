package com.wardrobapp.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the app reads off a product page.
 *
 * A page is untrusted input that arrives from a link somebody sent, so every
 * question here has a wrong answer that looks plausible: a logo taken for the
 * garment, a relative path resolved against the wrong directory, a title read
 * from the tab instead of the product.
 *
 * The images are checked *in order*, which is not cosmetic: the form fills its
 * gallery from this list and the first image becomes the garment's photo, so the
 * same images in a different order put a different picture on the card.
 *
 * These cases replace fourteen recorded product pages, which came from the app
 * this was ported from and went with it.
 */
class GarmentImportTest {

    private val pageUrl = "https://shop.example.com/clothing/product-42"

    private fun extract(html: String, url: String = pageUrl) =
        extractGarmentImportDataFromHtml(html, url)

    @Test
    fun `open graph is read first, and labelled`() {
        val extracted = extract(
            """
            <html><head>
              <meta property="og:image" content="https://cdn.example.com/front.jpg" />
              <meta property="og:title" content="Linen Shirt" />
              <meta property="product:brand" content="EXAMPLE BRAND" />
            </head><body></body></html>
            """.trimIndent()
        )

        assertEquals(listOf("https://cdn.example.com/front.jpg"), extracted.imageUrls)
        assertEquals("Linen Shirt", extracted.title)
        assertEquals(ImportParser.OPEN_GRAPH, extracted.parser)
        assertEquals(emptyList(), extracted.warnings)
    }

    @Test
    fun `a page's own JSON is read, and labelled`() {
        val extracted = extract(
            """
            <html><head>
            <script type="application/ld+json">
            {"@type":"Product","name":"Wool Coat","brand":{"name":"Example"},
             "image":["https://cdn.example.com/coat-1.jpg","https://cdn.example.com/coat-2.jpg"]}
            </script>
            </head><body></body></html>
            """.trimIndent()
        )

        assertEquals(
            listOf("https://cdn.example.com/coat-1.jpg", "https://cdn.example.com/coat-2.jpg"),
            extracted.imageUrls,
        )
        assertEquals("Wool Coat", extracted.title)
        assertEquals("Example", extracted.brand)
        assertEquals(ImportParser.JSON_LD, extracted.parser)
    }

    @Test
    fun `a JSON block that is not JSON is a warning, not a failure`() {
        // The rest of the page is still worth reading, and the person importing
        // should be told that something was skipped rather than left wondering
        // why only one image arrived.
        val extracted = extract(
            """
            <html><head>
            <script type="application/ld+json">{ this is not json }</script>
            <meta property="og:image" content="https://cdn.example.com/front.jpg" />
            </head></html>
            """.trimIndent()
        )

        assertEquals(listOf("https://cdn.example.com/front.jpg"), extracted.imageUrls)
        assertTrue(ImportWarning.StructuredDataUnreadable in extracted.warnings)
    }

    @Test
    fun `img tags are the last resort, and labelled as such`() {
        val extracted = extract(
            """
            <html><body>
              <img src="https://cdn.example.com/a.jpg">
              <img src="https://cdn.example.com/b.png">
            </body></html>
            """.trimIndent()
        )

        assertEquals(
            listOf("https://cdn.example.com/a.jpg", "https://cdn.example.com/b.png"),
            extracted.imageUrls,
        )
        assertEquals(ImportParser.HTML_IMAGES, extracted.parser)
    }

    @Test
    fun `a page read by more than one parser says so`() {
        val extracted = extract(
            """
            <html><head>
              <meta property="og:image" content="https://cdn.example.com/og.jpg" />
            </head><body>
              <img src="https://cdn.example.com/body.jpg">
            </body></html>
            """.trimIndent()
        )

        assertEquals(ImportParser.MIXED, extracted.parser)
    }

    @Test
    fun `a page with no images at all is labelled as such`() {
        val extracted = extract("<html><head><title>Shop</title></head><body></body></html>")

        assertEquals(emptyList(), extracted.imageUrls)
        assertEquals(ImportParser.NONE, extracted.parser)
    }

    @Test
    fun `the same image listed twice is taken once, keeping its first position`() {
        val extracted = extract(
            """
            <html><head>
              <meta property="og:image" content="https://cdn.example.com/a.jpg" />
            </head><body>
              <img src="https://cdn.example.com/b.jpg">
              <img src="https://cdn.example.com/a.jpg">
            </body></html>
            """.trimIndent()
        )

        assertEquals(
            listOf("https://cdn.example.com/a.jpg", "https://cdn.example.com/b.jpg"),
            extracted.imageUrls,
        )
    }

    @Test
    fun `furniture is left on the page`() {
        // A logo or a placeholder is not the garment, and it is the image most
        // likely to appear first.
        val extracted = extract(
            """
            <html><body>
              <img src="https://cdn.example.com/assets/logo.png">
              <img src="https://cdn.example.com/favicon.ico">
              <img src="https://cdn.example.com/ui/sprite.png">
              <img src="https://cdn.example.com/img/placeholder.jpg">
              <img src="https://cdn.example.com/users/avatar.jpg">
              <img src="https://cdn.example.com/products/shirt.jpg">
            </body></html>
            """.trimIndent()
        )

        assertEquals(listOf("https://cdn.example.com/products/shirt.jpg"), extracted.imageUrls)
    }

    @Test
    fun `something that is not an image is not taken for one`() {
        val extracted = extract(
            """
            <html><body>
              <img src="https://cdn.example.com/tracker.svg">
              <img src="https://cdn.example.com/video.mp4">
              <img src="https://cdn.example.com/real.webp">
            </body></html>
            """.trimIndent()
        )

        assertEquals(listOf("https://cdn.example.com/real.webp"), extracted.imageUrls)
    }

    @Test
    fun `a relative path is resolved against the page it came from`() {
        val extracted = extract(
            """
            <html><body>
              <img src="photo.jpg">
              <img src="/absolute/photo.jpg">
              <img src="../up/photo.jpg">
              <img src="//cdn.example.com/scheme-relative.jpg">
            </body></html>
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "https://shop.example.com/clothing/photo.jpg",
                "https://shop.example.com/absolute/photo.jpg",
                "https://shop.example.com/up/photo.jpg",
                "https://cdn.example.com/scheme-relative.jpg",
            ),
            extracted.imageUrls,
        )
    }

    @Test
    fun `the title falls back in the order that gets it right most often`() {
        // Structured data, then og:title, then twitter:title, then the tab. The
        // tab is last because it is the one most likely to say "Shop | Example".
        val product = """{"@type":"Product","name":"From JSON"}"""
        val allFour = """
            <html><head>
              <script type="application/ld+json">$product</script>
              <meta property="og:title" content="From OG" />
              <meta name="twitter:title" content="From Twitter" />
              <title>From Tab</title>
            </head></html>
        """.trimIndent()

        assertEquals("From JSON", extract(allFour).title)
        assertEquals(
            "From OG",
            extract(allFour.replace("""<script type="application/ld+json">$product</script>""", "")).title,
        )
        assertEquals(
            "From Twitter",
            extract(
                """
                <html><head>
                  <meta name="twitter:title" content="From Twitter" />
                  <title>From Tab</title>
                </head></html>
                """.trimIndent()
            ).title,
        )
        assertEquals("From Tab", extract("<html><head><title>From Tab</title></head></html>").title)
    }

    @Test
    fun `the brand falls back to the host, tidied up`() {
        // The label before the public suffix, which is the shop's name on almost
        // every retail domain -- and title-cased, since a hostname is lowercase
        // and a brand on a form is not.
        assertEquals("Cool Brand", extract("<html></html>", "https://www.cool-brand.com/p/1").brand)
        assertEquals("Example", extract("<html></html>", "https://shop.example.com/p").brand)
    }

    @Test
    fun `HTML entities in a title are decoded`() {
        val extracted = extract(
            "<html><head><title>Men&#39;s Shirt &amp; Tie</title></head></html>"
        )

        assertEquals("Men's Shirt & Tie", extracted.title)
    }

    @Test
    fun `the source URL comes back normalized, and a refused page is refused here too`() {
        assertEquals("https://shop.example.com/clothing/product-42", extract("<html></html>").sourceUrl)

        // The extractor resolves relative images against the page's address, so
        // it has to check that address itself rather than trust the caller.
        val thrown = runCatching { extract("<html></html>", "http://192.168.1.1/p") }.exceptionOrNull()
        assertTrue(thrown is UnsafeUrlException, "a local page was extracted from")
    }

    @Test
    fun `an image the page points at the local network survives extraction`() {
        // Deliberate, and worth stating: the extractor reports what the page
        // said, and the refusal happens where the fetching does. A port that
        // filtered here would look safer and would have moved a decision out of
        // the place that is tested for making it.
        val page = """<img src="http://192.168.1.1/cam.jpg" />"""
        val extracted = extractGarmentImportDataFromHtml(page, "https://example.com/p")

        assertTrue("http://192.168.1.1/cam.jpg" in extracted.imageUrls)
    }

    @Test
    fun `every warning has a sentence, and no two share one`() {
        val warnings = listOf(
            ImportWarning.StructuredDataUnreadable,
            ImportWarning.ImagesCapped(listed = 12, used = 6),
            ImportWarning.ImagesBlocked(count = 2),
            ImportWarning.ImagesFailed(count = 1),
        )

        val messages = warnings.map { it.englishMessage() }

        assertTrue(messages.all { it.isNotBlank() })
        assertEquals(messages.size, messages.toSet().size, "two warnings read the same")
        // A count that is not in its own sentence is a sentence that cannot be
        // acted on.
        assertTrue("12" in warnings[1].englishMessage() && "6" in warnings[1].englishMessage())
        assertTrue("2" in warnings[2].englishMessage())
    }
}
