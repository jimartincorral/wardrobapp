package com.wardrobapp.presentation

import com.wardrobapp.domain.Season

/**
 * Garment form state, as pure transitions.
 *
 * A Compose screen holds one of these and calls these functions; it should not
 * contain the rules. Nothing here knows about image pickers or permissions --
 * that is the platform layer's business -- which is what lets all of it be
 * tested without an emulator.
 */
data class GarmentFormState(
    val imageUris: List<String> = emptyList(),
    /**
     * Background-removed photos, positionally aligned with [imageUris]: entry
     * `i` is the cut-out of photo `i`, or "" where there is none. That alignment
     * is why removals and reorders must touch both lists together.
     */
    val bgRemovedUris: List<String> = emptyList(),
    val selectedImageIndex: Int = 0,
    val category: String = "tops",
    val subcategories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val seasons: List<Season> = emptyList(),
    val brand: String = "",
    val colorPalette: List<String> = listOf(DEFAULT_COLOR),
    val size: String = "",
) {
    companion object {
        const val DEFAULT_COLOR = "#000000"
        const val BRAND_SUGGESTION_LIMIT = 8
    }

    /** One entry per photo: what to show, and the original behind it. */
    data class GalleryItem(val uri: String, val original: String)

    /** Bring the state into shape: the lists aligned, the palette non-empty. */
    fun normalized(): GarmentFormState = copy(
        bgRemovedUris = imageUris.indices.map { bgRemovedUris.getOrNull(it) ?: "" },
        colorPalette = colorPalette.ifEmpty { listOf(DEFAULT_COLOR) },
    )

    /** Add a photo, or replace the one currently selected. */
    fun withImage(uri: String, replaceCurrent: Boolean = false): GarmentFormState {
        if (replaceCurrent && imageUris.getOrNull(selectedImageIndex)?.isNotEmpty() == true) {
            return copy(
                imageUris = imageUris.mapIndexed { i, item -> if (i == selectedImageIndex) uri else item },
                // The old cut-out was of the old photo.
                bgRemovedUris = bgRemovedUris.mapIndexed { i, item ->
                    if (i == selectedImageIndex) "" else item
                },
            )
        }

        return copy(
            imageUris = imageUris + uri,
            bgRemovedUris = bgRemovedUris + "",
            selectedImageIndex = imageUris.size,
        )
    }

    /** Remove a photo, keeping the selection pointing at something. */
    fun withoutImageAt(index: Int): GarmentFormState {
        val remaining = imageUris.filterIndexed { i, _ -> i != index }

        return copy(
            imageUris = remaining,
            bgRemovedUris = bgRemovedUris.filterIndexed { i, _ -> i != index },
            selectedImageIndex = when {
                remaining.isEmpty() -> 0
                selectedImageIndex > index -> selectedImageIndex - 1
                else -> minOf(selectedImageIndex, remaining.size - 1)
            },
        )
    }

    /** Move a photo, carrying its cut-out, and follow it with the selection. */
    fun withImagesReordered(fromIndex: Int, toIndex: Int): GarmentFormState {
        if (fromIndex == toIndex) return this
        if (fromIndex !in imageUris.indices || toIndex !in imageUris.indices) return this

        fun <T> move(items: List<T>): List<T> {
            val next = items.toMutableList()
            next.add(toIndex, next.removeAt(fromIndex))
            return next
        }

        return copy(
            imageUris = move(imageUris),
            bgRemovedUris = move(bgRemovedUris),
            selectedImageIndex = toIndex,
        )
    }

    /** Record a cut-out for the selected photo, or clear it with "". */
    fun withBackgroundRemoved(uri: String): GarmentFormState = copy(
        bgRemovedUris = bgRemovedUris.mapIndexed { i, item ->
            if (i == selectedImageIndex) uri else item
        },
    )

    /**
     * Choosing a garment type implies seasons (a blazer is not summerwear), so
     * they are filled in -- but only while the user has chosen none, so an
     * explicit choice is never overwritten.
     */
    fun withSubcategories(
        next: List<String>,
        seasonsFor: (List<String>) -> List<Season>,
    ): GarmentFormState = copy(
        subcategories = next,
        seasons = seasons.ifEmpty { seasonsFor(next) },
    )

    /**
     * Put a detected colour first, keeping what the user already picked.
     *
     * Replacing the palette would discard a deliberate choice; a detection is a
     * suggestion, not a correction.
     */
    fun withDetectedColor(color: String): GarmentFormState = copy(
        colorPalette = listOf(color) + colorPalette.filterNot { it == color },
    )

    /**
     * Apply an imported preview, keeping a brand already typed. An import is a
     * starting point, so it must not overwrite work in progress.
     */
    fun withImportedPreview(downloadedImageUris: List<String>, importedBrand: String?): GarmentFormState = copy(
        imageUris = downloadedImageUris,
        bgRemovedUris = downloadedImageUris.map { "" },
        selectedImageIndex = 0,
        brand = if (brand.isNotBlank()) brand else (importedBrand ?: ""),
    )

    /** What to show for each photo: the cut-out where there is one. */
    fun galleryItems(): List<GalleryItem> = imageUris.mapIndexed { index, uri ->
        GalleryItem(
            uri = bgRemovedUris.getOrNull(index)?.ifEmpty { null } ?: uri,
            original = uri,
        )
    }

    /** What the preview shows for the selected photo, if anything. */
    fun displayedPreviewUri(): String? =
        bgRemovedUris.getOrNull(selectedImageIndex)?.ifEmpty { null }
            ?: imageUris.getOrNull(selectedImageIndex)?.ifEmpty { null }

    /**
     * Whether the selected photo has a with-background original distinct from
     * its cut-out.
     *
     * A garment imported as a cut-out only has the same path in both slots, so
     * there is nothing to undo to and nothing to re-run removal against.
     */
    fun selectedHasOriginal(): Boolean {
        val photo = imageUris.getOrNull(selectedImageIndex) ?: return false
        return photo.isNotEmpty() && photo != bgRemovedUris.getOrNull(selectedImageIndex)
    }
}

/** Add a value if absent, remove it if present. */
fun <T> List<T>.toggled(value: T): List<T> =
    if (contains(value)) filterNot { it == value } else this + value

/**
 * Brand suggestions for what has been typed so far.
 *
 * An empty field offers everything -- the list doubles as a picker. An exact
 * match is dropped: the user has already typed it, so suggesting it is noise.
 */
fun brandSuggestions(
    known: List<String>,
    typed: String,
    limit: Int = GarmentFormState.BRAND_SUGGESTION_LIMIT,
): List<String> {
    val needle = typed.trim().lowercase()

    return known
        .filter { brand ->
            val candidate = brand.lowercase()
            when {
                candidate == needle -> false
                needle.isEmpty() -> true
                else -> candidate.contains(needle)
            }
        }
        .take(limit)
}
