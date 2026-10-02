package com.wardrobapp.domain

/**
 * Importing a garment from a link, end to end.
 *
 * The network is a parameter. [PageFetcher] and [ImageFetcher] are what :net
 * and :app supply, and everything that decides what happens -- which addresses are
 * fetched at all, how much of a page is read, how many images are taken, and
 * what the user is told about the difference -- is here, where it runs without
 * one.
 *
 * That split is not decoration. This is the path where a page the user did not
 * choose gets to name addresses the app then dials, so the caps and the refusals
 * are the feature. A version of this with `fetch` hardcoded in the middle could
 * only be tested by standing up a server, which is why the equivalent in the app
 * this replaced had no test for the ordering, the caps or any of the warnings.
 *
 * It was never compared against that app the way the extraction was: the function
 * it mirrors calls `fetch` and dynamically imports Expo's image service, and
 * nothing in a JVM test can run either. So the behaviour is pinned by the tests
 * next to it, written from that implementation line by line.
 */

/** Content types that can contain a product page. */
private val HTML_CONTENT_TYPES =
    listOf("text/html", "application/xhtml+xml", "text/plain", "application/xml")

/**
 * Fetch a page and take a garment off it.
 *
 * Throws [UnsafeUrlException] for an address the app will not touch, and
 * [GarmentImportException] for a page that could not be turned into a garment.
 * Both carry a reason a screen can translate.
 */
fun importGarmentFromUrl(
    inputUrl: String,
    fetchPage: PageFetcher,
    fetchImage: ImageFetcher,
): ImportedGarmentPreview {
    val sourceUrl = safeImportUrl(inputUrl)
    val response = fetchPage.fetch(sourceUrl)

    // Where it actually ended up. Refusing to read the response is what stops
    // anything coming back out of a redirect onto the local network.
    checkFetchedUrl(response.finalUrl, sourceUrl)

    if (response.status !in 200..299) {
        throw GarmentImportException(ImportFailureReason.PageNotLoaded(response.status))
    }

    val contentType = response.contentType?.lowercase()
    if (!contentType.isNullOrEmpty() && HTML_CONTENT_TYPES.none { contentType.contains(it) }) {
        throw GarmentImportException(ImportFailureReason.NotAWebPage)
    }

    val html = readBoundedText(response)
    val extracted = extractGarmentImportDataFromHtml(html, sourceUrl)

    if (extracted.imageUrls.isEmpty()) {
        throw GarmentImportException(ImportFailureReason.NoImagesFound)
    }

    // The image URLs come out of the page's own HTML, so they are as untrusted as
    // the page is: without this, a page could point them at the local network and
    // have the app fetch each one.
    val fetchable = extracted.imageUrls.filter { imageUrl ->
        try {
            safeImportUrl(imageUrl)
            true
        } catch (_: UnsafeUrlException) {
            false
        }
    }

    val blocked = extracted.imageUrls.size - fetchable.size
    if (fetchable.isEmpty()) {
        throw GarmentImportException(ImportFailureReason.NoFetchableImages)
    }

    val wanted = fetchable.take(MAX_IMPORTED_IMAGES)
    val downloaded = mutableListOf<String>()
    var failed = 0
    for (imageUrl in wanted) {
        try {
            downloaded += fetchImage.download(imageUrl)
        } catch (_: Exception) {
            // One photo missing is not a failed import; the count is reported
            // below. Any exception, because this is a network call and the
            // reasons it fails are not this function's business.
            failed++
        }
    }

    val warnings = extracted.warnings.toMutableList()
    if (fetchable.size > wanted.size) {
        warnings += ImportWarning.ImagesCapped(listed = fetchable.size, used = wanted.size)
    }
    if (blocked > 0) {
        warnings += ImportWarning.ImagesBlocked(blocked)
    }
    if (failed > 0) {
        warnings += ImportWarning.ImagesFailed(failed)
    }

    if (downloaded.isEmpty()) {
        throw GarmentImportException(ImportFailureReason.NoImagesDownloaded)
    }

    return ImportedGarmentPreview(
        sourceUrl = extracted.sourceUrl,
        title = extracted.title,
        brand = extracted.brand,
        imageUrls = wanted,
        downloadedImageUris = downloaded,
        warnings = warnings,
        parser = extracted.parser,
    )
}

/**
 * Read a response, refusing one too large to parse.
 *
 * The declared length is checked first and is the only check that saves the
 * memory: a response that says how big it is can be refused before it is read. A
 * response that does not say is read and then refused, which catches the parsing
 * and everything after it rather than the read itself.
 */
private fun readBoundedText(response: FetchedPage): String {
    val declared = response.declaredLength
    if (declared != null && declared > MAX_PAGE_CHARS) {
        throw GarmentImportException(ImportFailureReason.PageTooLarge)
    }

    val text = response.readText()
    if (text.length > MAX_PAGE_CHARS) {
        throw GarmentImportException(ImportFailureReason.PageTooLarge)
    }

    return text
}
