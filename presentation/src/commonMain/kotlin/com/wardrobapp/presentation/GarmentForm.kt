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
    /**
     * Whether [colorPalette] is somebody's choice rather than a starting point.
     *
     * A new garment starts on the default colour because a garment must have one,
     * and that default is not a choice -- it is what nobody has said anything about
     * yet. Detection replaces it; detection never replaces a choice. True as soon as
     * a colour is tapped, and true from the moment an existing garment is loaded,
     * since its palette is what was saved.
     *
     * Form state, never stored: the garment's row has colours, not the story of how
     * they got there.
     */
    val colorsChosen: Boolean = false,
    val size: String = "",
) {
    companion object {
        const val DEFAULT_COLOR = "#000000"
        const val BRAND_SUGGESTION_LIMIT = 8
    }

    /** One entry per photo: what to show, and the original behind it. */
    data class GalleryItem(val uri: String, val original: String)

    /** What a garment's photo columns should hold, and what is left over. */
    data class ImagesToStore(
        val imageUris: List<String>,
        val bgRemovedUris: List<String>,
        /** Originals nothing points at any more, safe to delete. */
        val discardable: List<String>,
    )

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
     * The form as a gap analysis would have it filled in.
     *
     * A garment the wardrobe was told it was missing arrives already described --
     * a category, a type and a colour -- and making somebody retype that is making
     * them do the work the app just did.
     *
     * [colorsChosen] stays false, which looks wrong and is the point: a suggested
     * colour is what would *work*, not what the garment in your hand actually is.
     * Leaving it unchosen lets detection from the photo replace it, so somebody
     * told "a black one would go with everything" who comes home with a charcoal
     * one gets charcoal recorded. Tapping any swatch still makes it a choice, the
     * same as anywhere else.
     */
    fun prefilledFor(
        category: String,
        subcategory: String?,
        colour: String?,
        seasonsFor: (List<String>) -> List<Season>,
    ): GarmentFormState = copy(
        category = category,
        colorPalette = listOfNotNull(colour?.takeIf { it.isNotBlank() })
            .ifEmpty { listOf(DEFAULT_COLOR) },
        colorsChosen = false,
    )
        .withSubcategories(listOfNotNull(subcategory?.takeIf { it.isNotBlank() }), seasonsFor)
        .normalized()

    /**
     * Collapse the photos into what gets stored.
     *
     * A slot whose background was removed stores the cut-out in *both* columns
     * and lets the original go: saving space is the whole point of removing it,
     * and keeping both would mean every removal costing more storage rather than
     * less.
     *
     * Both mistakes here are quiet ones. Discard a file something still points at
     * and the garment shows a gap where a photo was; miss one and it sits on the
     * phone forever with nothing referring to it. So this decides and hands back
     * the list to act on, rather than doing it.
     *
     * Idempotent on purpose: editing a garment whose photo was already collapsed
     * runs it again, and the second run must find nothing to discard.
     */
    fun imagesToStore(): ImagesToStore {
        val stored = mutableListOf<String>()
        val cutouts = mutableListOf<String>()
        val discardable = mutableListOf<String>()

        // Read positionally off the photos rather than re-aligning first: callers
        // hand over a normalized state, so the columns already line up, and a
        // second alignment here would be a step no test could tell apart from
        // the first.
        imageUris.forEachIndexed { index, original ->
            val cutout = bgRemovedUris.getOrNull(index) ?: ""

            if (cutout.isNotEmpty()) {
                stored.add(cutout)
                cutouts.add(cutout)
                // Only when it is genuinely a different file: after a previous
                // collapse the two are the same path, and discarding it would
                // delete the photo.
                if (original.isNotEmpty() && original != cutout) discardable.add(original)
            } else {
                stored.add(original)
                cutouts.add("")
            }
        }

        return ImagesToStore(stored, cutouts, discardable)
    }

    /**
     * Toggle a colour in the palette.
     *
     * A garment always has at least one colour, so removing the last one puts the
     * default back rather than leaving the palette empty. The React Native screen
     * did this inline next to the picker; having it here means one rule rather
     * than one per screen.
     */
    fun withColorToggled(color: String): GarmentFormState {
        val palette = colorPalette.toggled(color)

        return copy(
            colorPalette = palette.ifEmpty { listOf(DEFAULT_COLOR) },
            // Tapping any colour -- including tapping the default off and on again
            // -- makes the palette a choice, and choices are not detected over.
            colorsChosen = true,
        )
    }

    /**
     * Apply the colours read off the photo.
     *
     * The palette becomes what was detected, one colour or two, rather than the
     * detected colour joined to what was already there. That "joined to" is what
     * left every garment carrying the default black next to its real colour: black
     * is where a new garment starts, so prepending to it kept it.
     *
     * Which is why replacing is safe here and would not have been before: it only
     * happens while [colorsChosen] is false, so there is nothing to discard. Once
     * somebody has tapped a colour, or the garment came out of the database with
     * colours on it, detection changes nothing at all.
     *
     * An empty [colors] -- a photo with nothing countable in it -- also changes
     * nothing, rather than emptying the palette.
     */
    fun withDetectedColors(colors: List<String>): GarmentFormState =
        if (colorsChosen || colors.isEmpty()) this else copy(colorPalette = colors)

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
