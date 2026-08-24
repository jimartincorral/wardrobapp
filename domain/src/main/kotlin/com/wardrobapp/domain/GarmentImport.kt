package com.wardrobapp.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reading a garment out of a product page.
 *
 * A port of the extraction half of `src/services/url-import-service.ts`. The
 * fetch is not here -- it needs a network client, so it lives in :app -- and
 * everything that decides *what a page says* is, where it can be tested against
 * real pages without one.
 *
 * Three parsers, in a fixed order, because pages disagree about which they
 * provide: Open Graph and Twitter's card tags, JSON-LD `Product` nodes, and
 * failing both, the `<img>` tags themselves. They are not alternatives -- images
 * from all three are collected, in that order, de-duplicated -- and [parser]
 * records which of them actually produced something, which is worth showing
 * because "we scraped the images" and "the shop told us" are different levels of
 * confidence.
 *
 * Parsed with regular expressions rather than an HTML parser, exactly as the
 * TypeScript does. That is not the better way to read HTML; it is the way that
 * agrees with the app this is a port of, which is the property under test.
 * `garment-import.jsonl` pins all of it -- the shapes JSON-LD comes in, the lazy
 * loading attributes, picking the widest `srcset` candidate, the images refused
 * for being logos or not images at all, and the order the whole thing comes out
 * in.
 */

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

/** Image URLs that are furniture rather than the product. */
private val IMAGE_BLOCKLIST = listOf(
    Regex("/logo", RegexOption.IGNORE_CASE),
    Regex("/icon", RegexOption.IGNORE_CASE),
    Regex("favicon", RegexOption.IGNORE_CASE),
    Regex("sprite", RegexOption.IGNORE_CASE),
    Regex("avatar", RegexOption.IGNORE_CASE),
    Regex("placeholder", RegexOption.IGNORE_CASE),
)

private val SUPPORTED_IMAGE_EXTENSIONS =
    setOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".avif")

private val TAG_ATTRIBUTE =
    Regex("([:@a-zA-Z0-9_-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")

private val DOCUMENT_TITLE =
    Regex("<title\\b[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)

private val JSON_LD_SCRIPT = Regex(
    "<script\\b[^>]*type=([\"'])application/ld\\+json\\1[^>]*>([\\s\\S]*?)</script>",
    RegexOption.IGNORE_CASE,
)

private val FILE_EXTENSION = Regex("\\.[a-z0-9]+$", RegexOption.IGNORE_CASE)

private val WHITESPACE = Regex("\\s+")

private val BRAND_SEPARATORS = Regex("[\\s._-]+")

/**
 * A page's own JSON, read as leniently as it can be without guessing.
 *
 * `ignoreUnknownKeys` because a `Product` node carries dozens of fields this app
 * has no use for, and `isLenient` stays off: `JSON.parse` on the other side does
 * not accept unquoted keys either, and a block that is not JSON should produce
 * the warning rather than a silent misreading.
 */
private val json = Json { ignoreUnknownKeys = true }

/**
 * Everything a page says about the garment on it.
 *
 * Throws [UnsafeUrlException] if the page's own address is one the app will not
 * touch, which is the same check the fetch made -- repeated here because this is
 * also the base every relative image URL is resolved against.
 */
fun extractGarmentImportDataFromHtml(html: String, pageUrl: String): ImportedGarmentData {
    val metaTags = extractTags(html, "meta")
    val images = LinkedHashSet<String>()
    val parserSources = LinkedHashSet<ImportParser>()
    val warnings = mutableListOf<ImportWarning>()

    fun addImages(found: List<String>, parser: ImportParser) {
        for (imageUrl in found) {
            if (images.add(imageUrl)) parserSources.add(parser)
        }
    }

    addImages(extractOpenGraphImages(metaTags, pageUrl), ImportParser.OPEN_GRAPH)

    val structured = extractJsonLdProductData(html, pageUrl)
    addImages(structured.images, ImportParser.JSON_LD)
    warnings += structured.warnings

    addImages(extractImageTagImages(html, pageUrl), ImportParser.HTML_IMAGES)

    val title = firstNonEmpty(
        structured.title,
        metaContent(metaTags, "property", "og:title"),
        metaContent(metaTags, "name", "twitter:title"),
        documentTitle(html),
    )

    val brand = firstNonEmpty(
        structured.brand,
        metaContent(metaTags, "property", "product:brand"),
        metaContent(metaTags, "property", "og:site_name"),
        hostnameToBrand(pageUrl),
    )

    return ImportedGarmentData(
        sourceUrl = safeImportUrl(pageUrl),
        title = title?.let { decodeHtmlEntities(it) },
        brand = brand?.let { prettifyBrand(it) },
        imageUrls = images.toList(),
        warnings = warnings,
        parser = resolveParser(parserSources),
    )
}

/**
 * Which parser to credit.
 *
 * More than one means the page provided several and they did not agree on the
 * same images -- worth saying, because it is the case where the list is part
 * product photography and part page furniture.
 */
private fun resolveParser(sources: Set<ImportParser>): ImportParser = when {
    sources.isEmpty() -> ImportParser.NONE
    sources.size > 1 -> ImportParser.MIXED
    else -> sources.first()
}

/**
 * Open Graph, and Twitter's cards alongside it.
 *
 * Counted as one source rather than two: a page that sets both is one page
 * describing itself, and splitting them would make [ImportParser.MIXED] mean
 * something duller than it does.
 */
private fun extractOpenGraphImages(metaTags: List<String>, pageUrl: String): List<String> {
    val candidates = listOfNotNull(
        metaContent(metaTags, "property", "og:image"),
        metaContent(metaTags, "property", "og:image:secure_url"),
        metaContent(metaTags, "name", "twitter:image"),
        metaContent(metaTags, "name", "twitter:image:src"),
    )

    return normalizeImageUrls(candidates, pageUrl)
}

/** What the JSON-LD said, and what could not be read. */
private data class StructuredData(
    val title: String?,
    val brand: String?,
    val images: List<String>,
    val warnings: List<ImportWarning>,
)

private fun extractJsonLdProductData(html: String, pageUrl: String): StructuredData {
    val warnings = mutableListOf<ImportWarning>()
    var title: String? = null
    var brand: String? = null
    val images = mutableListOf<String>()

    for (match in JSON_LD_SCRIPT.findAll(html)) {
        val rawJson = decodeHtmlEntities(match.groupValues[2]).trim()
        if (rawJson.isEmpty()) continue

        val parsed = try {
            json.parseToJsonElement(rawJson)
        } catch (_: Exception) {
            // Any failure, not a specific one: this is arbitrary text from a
            // page, and the answer to all of it is the same warning.
            warnings += ImportWarning.StructuredDataUnreadable
            continue
        }

        for (node in flattenJsonLdNodes(parsed)) {
            if (!looksLikeProductNode(node)) continue

            title = title ?: stringOrNull(node["name"])
            brand = brand ?: extractBrand(node["brand"])

            for (imageUrl in normalizeImageUrls(extractImageValues(node["image"]), pageUrl)) {
                if (imageUrl !in images) images += imageUrl
            }
        }
    }

    return StructuredData(title, brand, images, warnings)
}

/**
 * Every node in a JSON-LD document, however it is nested.
 *
 * An `@graph` is the common shape -- one document describing a page, a
 * breadcrumb trail and the product -- and the product is inside it rather than at
 * the top.
 */
private fun flattenJsonLdNodes(value: JsonElement?): List<JsonObject> = when (value) {
    is JsonArray -> value.flatMap { flattenJsonLdNodes(it) }
    is JsonObject -> {
        val graph = value["@graph"]
        if (graph is JsonArray) {
            listOf(value) + graph.flatMap { flattenJsonLdNodes(it) }
        } else {
            listOf(value)
        }
    }
    else -> emptyList()
}

/** `@type` naming a product, whether as a string or one of several. */
private fun looksLikeProductNode(node: JsonObject): Boolean {
    return when (val type = node["@type"]) {
        is JsonArray -> type.any {
            asString(it)?.lowercase()?.contains("product") == true
        }
        else -> asString(type)?.lowercase()?.contains("product") == true
    }
}

/** A brand written as a string, an object with a name, or a list of either. */
private fun extractBrand(value: JsonElement?): String? = when (value) {
    is JsonArray -> value.firstNotNullOfOrNull { extractBrand(it) }
    is JsonObject -> stringOrNull(value["name"]) ?: stringOrNull(value["@id"])
    else -> asString(value)
}

/** Image values, which schema.org allows in as many shapes as a brand. */
private fun extractImageValues(value: JsonElement?): List<String> = when (value) {
    is JsonArray -> value.flatMap { extractImageValues(it) }
    is JsonObject -> listOfNotNull(
        stringOrNull(value["url"]),
        stringOrNull(value["contentUrl"]),
        stringOrNull(value["@id"]),
    )
    else -> listOfNotNull(asString(value))
}

/**
 * The images a page draws, including the ones it has not drawn yet.
 *
 * The lazy-loading attributes are not optional extras: on a shop built around
 * them, `src` holds a placeholder and every real photograph is in `data-src` or
 * a `srcset`.
 */
private fun extractImageTagImages(html: String, pageUrl: String): List<String> {
    val imageUrls = mutableListOf<String>()

    for (tag in extractTags(html, "img")) {
        val attributes = parseTagAttributes(tag)
        val candidates = listOfNotNull(
            attributes["src"],
            attributes["data-src"],
            attributes["data-original"],
            attributes["data-image"],
            attributes["data-zoom"],
            largestSrcsetCandidate(attributes["srcset"]),
            largestSrcsetCandidate(attributes["data-srcset"]),
        )

        for (imageUrl in normalizeImageUrls(candidates, pageUrl)) {
            if (imageUrl !in imageUrls) imageUrls += imageUrl
        }
    }

    return imageUrls
}

/**
 * The widest candidate in a `srcset`.
 *
 * Widest rather than first because the first is usually the thumbnail, and a
 * garment photo is being stored to look at. A candidate with no width descriptor
 * counts as zero, so it only wins if nothing else is offered.
 */
private fun largestSrcsetCandidate(srcset: String?): String? {
    if (srcset == null) return null

    val candidates = srcset.split(',').mapNotNull { item ->
        // The first two whitespace-separated tokens and no more, which is what
        // JavaScript's `split(/\s+/, 2)` leaves behind -- it truncates rather
        // than keeping the remainder, so a third token is simply not seen.
        val tokens = item.trim().split(WHITESPACE)
        val url = tokens.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val descriptor = tokens.getOrNull(1)
        val width = descriptor
            ?.takeIf { it.endsWith("w") }
            ?.takeWhile { it.isDigit() }
            ?.toIntOrNull()
            ?: 0
        url to width
    }

    // Stable, so equal widths keep the order the page wrote them in.
    return candidates.sortedByDescending { it.second }.firstOrNull()?.first
}

private fun normalizeImageUrls(candidates: List<String>, pageUrl: String): List<String> {
    val normalized = mutableListOf<String>()

    for (candidate in candidates) {
        val absolute = normalizeImageUrl(candidate, pageUrl) ?: continue
        if (absolute !in normalized) normalized += absolute
    }

    return normalized
}

/**
 * One image URL, made absolute and judged.
 *
 * A `data:` URI is dropped rather than decoded: it is not something to fetch, and
 * the images that arrive this way are placeholders. An unknown extension is
 * dropped too, but *no* extension is allowed through -- plenty of image CDNs
 * serve from a path with none.
 */
private fun normalizeImageUrl(candidate: String, pageUrl: String): String? {
    val trimmed = candidate.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("data:")) return null

    val base = parseWebAddress(pageUrl) ?: return null
    val absolute = resolveWebAddress(trimmed, base) ?: return null
    if (absolute.scheme != "http" && absolute.scheme != "https") return null

    if (IMAGE_BLOCKLIST.any { it.containsMatchIn(absolute.path) }) return null

    val extension = FILE_EXTENSION.find(absolute.path)?.value?.lowercase()
    if (extension != null && extension !in SUPPORTED_IMAGE_EXTENSIONS) return null

    return absolute.serialize()
}

/** Every `<tag ...>` in the document, as written. */
private fun extractTags(html: String, tagName: String): List<String> =
    Regex("<$tagName\\b[^>]*>", RegexOption.IGNORE_CASE)
        .findAll(html)
        .map { it.value }
        .toList()

/** A tag's attributes, quoted or not, lower-cased and entity-decoded. */
private fun parseTagAttributes(tag: String): Map<String, String> {
    val attributes = mutableMapOf<String, String>()

    for (match in TAG_ATTRIBUTE.findAll(tag)) {
        val key = match.groupValues[1].lowercase()
        val value = match.groups[3]?.value
            ?: match.groups[4]?.value
            ?: match.groups[5]?.value
            ?: ""
        attributes[key] = decodeHtmlEntities(value)
    }

    return attributes
}

private fun metaContent(metaTags: List<String>, attribute: String, key: String): String? {
    val wanted = key.lowercase()

    for (tag in metaTags) {
        val attributes = parseTagAttributes(tag)
        if (attributes[attribute]?.lowercase() == wanted) {
            return attributes["content"]
        }
    }

    return null
}

private fun documentTitle(html: String): String? =
    DOCUMENT_TITLE.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The shop's name, guessed from its address.
 *
 * The label before the public suffix, which is right for `zara.com` and
 * `cos.com` and wrong for `example.co.uk` -- that gives "co". Faithful to the
 * TypeScript, and it is a prefill the user can correct rather than a fact.
 */
private fun hostnameToBrand(pageUrl: String): String? {
    val host = parseWebAddress(pageUrl)?.host ?: return null
    val parts = host.split('.').filter { it.isNotEmpty() }
    if (parts.size < 2) return host
    return parts[parts.size - 2]
}

/** `example-brand` as `Example Brand`. Only the first letter of each word. */
private fun prettifyBrand(brand: String): String = brand
    .trim()
    .replaceFirst(Regex("^www\\.", RegexOption.IGNORE_CASE), "")
    .split(BRAND_SEPARATORS)
    .filter { it.isNotEmpty() }
    .joinToString(" ") { it.replaceFirstChar { first -> first.uppercaseChar() } }

private fun firstNonEmpty(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }?.trim()

private fun stringOrNull(value: JsonElement?): String? =
    asString(value)?.trim()?.takeIf { it.isNotEmpty() }

/** A JSON string's content, or null for anything that is not one. */
private fun asString(value: JsonElement?): String? {
    val primitive = value as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content else null
}

/**
 * The handful of entities that appear in the attributes this reads.
 *
 * Not a general entity table, and deliberately the same handful the TypeScript
 * decodes: a page writing `&eacute;` in a title gets it back undecoded on both
 * sides, which is a shared shortcoming rather than a divergence.
 */
private fun decodeHtmlEntities(value: String): String = value
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&nbsp;", " ")
