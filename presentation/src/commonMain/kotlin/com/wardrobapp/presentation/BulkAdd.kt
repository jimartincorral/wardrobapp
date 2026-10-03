package com.wardrobapp.presentation

import com.wardrobapp.domain.Season

/**
 * Adding a wardrobe, rather than a garment.
 *
 * The single-garment form asks for everything about one garment. Cataloguing
 * clothes already owned is the opposite shape of job: many garments, and for each
 * one only the thing the photo cannot say, which is what kind of garment it is.
 * So this is a queue rather than a form -- one photo at a time, its category, and
 * on to the next.
 *
 * The queue is the remaining drafts and nothing else: the one being filled in is
 * the head, advancing drops it, and there is no index to fall out of step with
 * the list. [total] and [added] are counters over what has happened rather than
 * positions into anything, which is what makes "3 of 12" and "12 garments added"
 * answerable after the queue has drained.
 */
data class BulkAddState(
    /** Still to be filled in. The head is the one on screen. */
    val drafts: List<Draft> = emptyList(),
    /** How many photos have ever entered the queue, including those since dealt with. */
    val total: Int = 0,
    /** How many were saved as garments. */
    val added: Int = 0,
    /** How many were thrown away rather than saved. */
    val skipped: Int = 0,
) {
    /**
     * One garment-to-be.
     *
     * Colours arrive detected from the photo, as they do in the form, and the
     * queue carries no "chosen" flag because nothing here offers a colour to
     * choose: a palette that needs correcting is a job for the garment's own form,
     * after it exists.
     */
    data class Draft(
        /** The stored photo, resolved to something drawable. */
        val imageUri: String,
        /**
         * The cut-out of [imageUri], or "" where there is none.
         *
         * Both are kept while the draft is on screen so that removing a background
         * can be undone. The collapse at write time is the form's rule, not a
         * second one -- see [imagesToStore].
         */
        val cutoutUri: String = "",
        val category: String = DEFAULT_CATEGORY,
        val subcategories: List<String> = emptyList(),
        val seasons: List<Season> = emptyList(),
        val brand: String = "",
        val colorPalette: List<String> = listOf(GarmentFormState.DEFAULT_COLOR),
    ) {
        /** What to draw, and what to read colours from: the cut-out where there is one. */
        val displayUri: String get() = cutoutUri.ifEmpty { imageUri }

        /**
         * What this draft's photo columns should hold, and what is left over.
         *
         * Delegated to the form's own rule rather than restated: a cut-out is
         * stored in both columns and the original let go, and both mistakes there
         * are silent ones -- discard a file something still points at and the
         * garment shows a gap, miss one and it sits on the phone forever.
         */
        fun imagesToStore(): GarmentFormState.ImagesToStore = GarmentFormState(
            imageUris = listOf(imageUri),
            bgRemovedUris = listOf(cutoutUri),
        ).imagesToStore()
    }

    /** The draft being filled in, or null when the queue has drained. */
    val current: Draft? get() = drafts.firstOrNull()

    /** Which photo this is, counting from one, for "3 of 12". */
    val position: Int get() = total - drafts.size + 1

    /**
     * Whether the queue has been worked through.
     *
     * False before any photo has been picked, so an untouched screen does not
     * report a finished job it never started.
     */
    val isFinished: Boolean get() = total > 0 && drafts.isEmpty()

    /**
     * Take on more photos.
     *
     * Appended rather than replacing, so a second helping of photos joins the
     * queue instead of discarding what is left of the first.
     */
    fun withDraftsAdded(imageUris: List<String>): BulkAddState = copy(
        drafts = drafts + imageUris.map { Draft(imageUri = it) },
        total = total + imageUris.size,
    )

    /** A category for the draft on screen. Its types go with it: a type belongs to a category. */
    fun withCategory(category: String): BulkAddState =
        editingCurrent { it.copy(category = category, subcategories = emptyList()) }

    /**
     * Toggle a type on the draft on screen, filling in the seasons it implies.
     *
     * The same rule the form uses, for the same reason: a parka is not summerwear,
     * and a garment catalogued in a hurry with no seasons at all is a garment the
     * suggestion engine cannot place.
     */
    fun withSubcategoryToggled(
        subcategory: String,
        seasonsFor: (List<String>) -> List<Season>,
    ): BulkAddState = editingCurrent { draft ->
        val next = draft.subcategories.toggled(subcategory)
        draft.copy(subcategories = next, seasons = seasonsFor(next))
    }

    fun withBrand(brand: String): BulkAddState = editingCurrent { it.copy(brand = brand) }

    /**
     * Apply colours read off a photo.
     *
     * Keyed on the photo they were read from rather than on the head of the queue:
     * detection is slow enough that the queue can move on while it runs, and
     * colours from the previous garment are worse than the default. Empty colours
     * -- a photo with nothing countable in it -- change nothing rather than
     * emptying the palette.
     */
    fun withDetectedColors(readFrom: String, colors: List<String>): BulkAddState {
        if (colors.isEmpty()) return this

        return copy(
            drafts = drafts.map { draft ->
                // Against what the draft is *now* showing: a background removed
                // while detection ran makes the answer it came back with an answer
                // about the wrong pixels, and a cut-out's colours are the better
                // ones anyway.
                if (draft.displayUri == readFrom) draft.copy(colorPalette = colors) else draft
            },
        )
    }

    /**
     * A photo re-cropped.
     *
     * Keyed on the photo it replaces, and it clears that photo's cut-out: the
     * cut-out was of the uncropped photo, so it no longer describes what is there.
     * The form clears it for the same reason when a photo is replaced.
     */
    fun withPhotoReplaced(previous: String, next: String): BulkAddState = copy(
        drafts = drafts.map { draft ->
            if (draft.imageUri == previous) {
                draft.copy(imageUri = next, cutoutUri = "")
            } else {
                draft
            }
        },
    )

    /** A background removed from one photo. Keyed, because removal is slow. */
    fun withCutout(imageUri: String, cutoutUri: String): BulkAddState = copy(
        drafts = drafts.map { draft ->
            if (draft.imageUri == imageUri) draft.copy(cutoutUri = cutoutUri) else draft
        },
    )

    /** A removal undone: the photo goes back to being what is drawn. */
    fun withCutoutCleared(imageUri: String): BulkAddState = withCutout(imageUri, "")

    /** The draft on screen was saved. */
    fun advanced(): BulkAddState =
        if (drafts.isEmpty()) this else copy(drafts = drafts.drop(1), added = added + 1)

    /** The draft on screen was thrown away. */
    fun skipped(): BulkAddState =
        if (drafts.isEmpty()) this else copy(drafts = drafts.drop(1), skipped = skipped + 1)

    private fun editingCurrent(transform: (Draft) -> Draft): BulkAddState {
        val head = drafts.firstOrNull() ?: return this
        return copy(drafts = listOf(transform(head)) + drafts.drop(1))
    }

    companion object {
        /**
         * What a draft starts as.
         *
         * The same default the single-garment form opens on, and for the same
         * reason: a garment must have a category, so the queue starts on the
         * commonest one rather than refusing to move until something is tapped.
         */
        const val DEFAULT_CATEGORY = "tops"

        /**
         * How many photos one pass may take on.
         *
         * Each one is stored, decoded and read for its colours, so a hundred at
         * once is a long wait with nothing to show. A limit that is generous for
         * the job -- a wardrobe is catalogued a drawer at a time -- and small
         * enough to stay responsive.
         */
        const val MAX_PHOTOS = 30
    }
}
