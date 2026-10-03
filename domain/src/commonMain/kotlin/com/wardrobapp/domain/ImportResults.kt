package com.wardrobapp.domain

/**
 * What a URL import says back: its limits, its outcomes and the reasons it gives.
 *
 * Common code, unlike the import itself. The fetching, the extraction and the
 * address checks run only where a page is fetched -- on the phone, and later on
 * the Home Assistant server -- so they live in jvmMain beside the JVM APIs they
 * use. But every screen that shows an import's result needs these types, the
 * browser's included, which is why they were taken out of `GarmentImport.kt` and
 * `GarmentImportRun.kt` and put here unchanged.
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

/** Which parser produced the images. */
enum class ImportParser {
    OPEN_GRAPH,
    JSON_LD,
    HTML_IMAGES,
    MIXED,
    NONE,
}

/** Something worth saying about an import that still succeeded. */
sealed interface ImportWarning {

    /** A `ld+json` block that was not JSON. */
    data object StructuredDataUnreadable : ImportWarning

    /** The page listed more images than the app will take. */
    data class ImagesCapped(val listed: Int, val used: Int) : ImportWarning

    /** Images pointing somewhere the app will not fetch. */
    data class ImagesBlocked(val count: Int) : ImportWarning

    /** Images that were allowed but did not arrive. */
    data class ImagesFailed(val count: Int) : ImportWarning
}

/**
 * The sentence each warning has always produced.
 *
 * Byte-for-byte the TypeScript's, so the fixture can compare the English while
 * :app renders the same thing from a string resource. The singular and plural are
 * spelled out here for the same reason the messages are: this is the copy the
 * fixture compares, and Android's own plural rules take over in the app.
 */
fun ImportWarning.englishMessage(): String = when (this) {
    ImportWarning.StructuredDataUnreadable ->
        "Some structured product data could not be parsed."

    is ImportWarning.ImagesCapped ->
        "That page listed $listed images; the first $used were used."

    is ImportWarning.ImagesBlocked ->
        "$count image${if (count == 1) "" else "s"} pointed somewhere this app will not fetch."

    is ImportWarning.ImagesFailed ->
        "$count image${if (count == 1) "" else "s"} could not be downloaded."
}

/** What a page turned out to say about a garment. */
data class ImportedGarmentData(
    val sourceUrl: String,
    val title: String?,
    val brand: String?,
    val imageUrls: List<String>,
    val warnings: List<ImportWarning>,
    val parser: ImportParser,
)

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

/** Fetching a page. Implemented in :net; throws [GarmentImportException] on a timeout. */
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
