package com.wardrobapp.api

import io.ktor.http.encodeURLPathPart

/**
 * Every address the server answers, written once for both sides.
 *
 * The constants are the templates :server registers, with `{id}` and `{name}`
 * where a value goes; the functions fill them in for the client, encoding the
 * value. A route renamed here is renamed on both sides, and one that exists on
 * only one side does not compile.
 *
 * Relative, with no leading slash, on purpose. Home Assistant serves an app
 * through ingress, under a path it chooses per installation
 * (`/api/hassio_ingress/<token>/`), so the browser resolves these against the
 * page it was loaded from rather than against the root of the host. The server
 * itself sees them from its own root, ingress having stripped the prefix, and
 * puts a slash in front when it registers them.
 *
 * Grouped by what they are about rather than by screen: several screens read a
 * garment, and they all read it from the same place.
 */
object Routes {
    const val HOME_COUNTS = "api/home/counts"

    /** GET: the garments in use. POST [SavedGarment]: a new one from the form. */
    const val GARMENTS = "api/garments"

    /** GET, PUT [SavedGarment], DELETE. */
    const val GARMENT = "api/garments/{id}"

    /** PUT [Flag]: in use, or retired. */
    const val GARMENT_IN_USE = "api/garments/{id}/in-use"

    /** PUT [SavedPhotos]. */
    const val GARMENT_PHOTOS = "api/garments/{id}/photos"

    /** POST a WardrobeQuery: the wardrobe list, filtered and ordered. */
    const val GARMENT_SEARCH = "api/garments/search"

    /** POST a bulk add draft: one more garment. */
    const val GARMENT_BULK = "api/garments/bulk"

    /** POST a DuplicateCandidate: the garments it looks like. */
    const val GARMENT_DUPLICATES = "api/garments/duplicates"

    const val BRANDS = "api/brands"

    /** GET, with `archived=true` to include archived ones. POST an OutfitDraft. */
    const val OUTFITS = "api/outfits"

    /** GET, PUT an OutfitDraft, DELETE. */
    const val OUTFIT = "api/outfits/{id}"

    /** GET: the outfit with its garments and its rating. */
    const val OUTFIT_DETAIL = "api/outfits/{id}/detail"

    /** PUT [Rating]. */
    const val OUTFIT_RATING = "api/outfits/{id}/rating"

    /** PUT [Flag]. */
    const val OUTFIT_PINNED = "api/outfits/{id}/pinned"

    /** POST, no body. Only ever this way round: archiving is done by rating. */
    const val OUTFIT_UNARCHIVE = "api/outfits/{id}/unarchive"

    /** POST a SuggestionRequest. */
    const val SUGGESTIONS = "api/suggestions"

    /** POST a Suggestion: keep it as an outfit. */
    const val SUGGESTION_KEPT = "api/suggestions/kept"

    /** POST [SuggestionRating]. */
    const val SUGGESTION_RATING = "api/suggestions/rating"

    const val STATISTICS = "api/statistics"
    const val STATISTICS_DUPLICATES = "api/statistics/duplicates"
    const val STATISTICS_GAPS = "api/statistics/gaps"

    const val STORAGE = "api/storage"

    /** POST [ImportRequest]. */
    const val IMPORT = "api/import"

    /** POST the photo's bytes, typed by Content-Type; answers [StoredPhoto]. */
    const val PHOTOS = "api/photos"

    /** DELETE. */
    const val PHOTO = "api/photos/{name}"

    /**
     * GET: the photo itself. Outside `api/` because it is what an image's `src`
     * points at, and the references the garment rows hold become this once the
     * server reads them -- see the server's image directory.
     */
    const val PHOTO_FILE = "photos/{name}"

    /** The prefix every garment row's photo reference has, as the server reads them. */
    const val PHOTO_FILES = "photos/"

    fun garment(id: String) = GARMENT.with("id", id)
    fun garmentInUse(id: String) = GARMENT_IN_USE.with("id", id)
    fun garmentPhotos(id: String) = GARMENT_PHOTOS.with("id", id)
    fun outfit(id: String) = OUTFIT.with("id", id)
    fun outfitDetail(id: String) = OUTFIT_DETAIL.with("id", id)
    fun outfitRating(id: String) = OUTFIT_RATING.with("id", id)
    fun outfitPinned(id: String) = OUTFIT_PINNED.with("id", id)
    fun outfitUnarchive(id: String) = OUTFIT_UNARCHIVE.with("id", id)
    fun photo(name: String) = PHOTO.with("name", name)

    private fun String.with(parameter: String, value: String) =
        replace("{$parameter}", value.encodeURLPathPart())
}
