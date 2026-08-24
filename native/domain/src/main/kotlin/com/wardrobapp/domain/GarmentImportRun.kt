package com.wardrobapp.domain

/**
 * Importing a garment from a link, end to end.
 *
 * The network is a parameter. [PageFetcher] and [ImageFetcher] are what :app
 * supplies, and everything that decides what happens -- which addresses are
 * fetched at all, how much of a page is read, how many images are taken, and
 * what the user is told about the difference -- is here, where it runs without
 * one.
 *
 * That split is not decoration. This is the path where a page the user did not
 * choose gets to name addresses the app then dials, so the caps and the refusals
 * are the feature. A version of this with `fetch` hardcoded in the middle could
 * only be tested by standing up a server, which is why the TypeScript's own
 * equivalent has no test for the ordering, the caps or any of the warnings.
 *
 * Unlike the extraction, this is not held to the TypeScript by a fixture: the
 * function it mirrors calls `fetch` and dynamically imports Expo's image service,
 * neither of which the parity dump can run -- it imports only the layers that are
 * free of React Native by construction. So the behaviour is pinned by the tests
 * next to it, written from the TypeScript line by line.
 */

/**
 * How much of a page the app will read.
 *
 * A product page is tens of kilobytes; anything approaching this is not one.
 * Measured in characters, as the TypeScript measures it, which for HTML is close
 * enough at this size.
 */
const val MAX_PAGE_CHARS = 4 * 1024 * 1024

/**
 * How many images the app will take from one page.
 *
 * The list comes out of the page's own HTML, so its length is the page's choice:
 * without a cap, one link means as many requests as it cares to name. A garment
 * needs a handful of photos and the form shows a gallery, not a catalogue.
 */
const val MAX_IMPORTED_IMAGES = 8

/** Content types that can contain a product page. */
private val HTML_CONTENT_TYPES =
    listOf("text/html", "application/xhtml+xml", "text/plain", "application/xml")

/** Why an import produced nothing. */
sealed interface ImportFailureReason {

    /** The server did not answer inside the deadline. */
    data object PageTimedOut : ImportFailureReason

    /** Bigger than this app will read. */
    data object PageTooLarge : ImportFailureReason

    /** Answered, but not with a page. */
    data class PageNotLoaded(val status: Int) : ImportFailureReason

    /** A PDF, an image, a download -- something that is not a web page. */
    data object NotAWebPage : ImportFailureReason

    /** A page, but with no garment on it. */
    data object NoImagesFound : ImportFailureReason

    /** Images, but every one of them somewhere the app will not go. */
    data object NoFetchableImages : ImportFailureReason

    /** Images this app would fetch, none of which arrived. */
    data object NoImagesDownloaded : ImportFailureReason
}

/** A failure worth showing someone, carrying the reason so it can be translated. */
class GarmentImportException(val reason: ImportFailureReason) :
    Exception(reason.englishMessage())

/** Byte-for-byte the sentences `url-import-service.ts` throws. */
fun ImportFailureReason.englishMessage(): String = when (this) {
    ImportFailureReason.PageTimedOut ->
        "That page took too long to answer."

    ImportFailureReason.PageTooLarge ->
        "That page is too large to read."

    is ImportFailureReason.PageNotLoaded ->
        "Could not load page ($status)."

    ImportFailureReason.NotAWebPage ->
        "That address is not a web page."

    ImportFailureReason.NoImagesFound ->
        "No garment images were found on that page."

    ImportFailureReason.NoFetchableImages ->
        "The images on that page are not ones this app will download."

    ImportFailureReason.NoImagesDownloaded ->
        "Images were found, but none could be downloaded."
}

/**
 * A page as it came back.
 *
 * [finalUrl] is where the request actually ended up, which is the whole reason
 * this type exists rather than a plain string: a permitted address can redirect
 * to a private one, and the response must not be read until that has been
 * checked.
 *
 * [readText] is a function, not a string, so the body is only pulled into memory
 * after the headers have been judged -- a page that declares four megabytes is
 * refused without reading it.
 */
class FetchedPage(
    val finalUrl: String?,
    val status: Int,
    val contentType: String?,
    val declaredLength: Long?,
    val readText: () -> String,
)

/** Fetching a page. Implemented in :app; throws [GarmentImportException] on a timeout. */
fun interface PageFetcher {
    fun fetch(url: String): FetchedPage
}

/**
 * Downloading one image to a local file, returning where it landed.
 *
 * Throws for an image that did not arrive: the caller counts the failures and
 * says how many, rather than abandoning an import over one missing photo.
 */
fun interface ImageFetcher {
    fun download(url: String): String
}

/** What an import came back with, ready for the form to be filled from. */
data class ImportedGarmentPreview(
    val sourceUrl: String,
    val title: String?,
    val brand: String?,
    val imageUrls: List<String>,
    val downloadedImageUris: List<String>,
    val warnings: List<ImportWarning>,
    val parser: ImportParser,
)

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
