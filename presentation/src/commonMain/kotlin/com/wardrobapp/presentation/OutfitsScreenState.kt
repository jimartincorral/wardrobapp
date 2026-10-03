package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.OutfitRecord
import com.wardrobapp.data.SuggestedOutfit
import kotlinx.serialization.Serializable

/**
 * What the outfits screen is given to draw.
 *
 * Was `OutfitsViewModel.State` in :app. It moved here unchanged when the
 * screens started moving to Compose Multiplatform: a screen's state is what a
 * shared screen takes, so it has to be common code, and here it is compiled
 * and tested on every machine rather than only where there is an Android SDK.
 * The ViewModel that fills it stays in :app.
 */

data class OutfitsScreenState(
    val filters: OutfitFilters = OutfitFilters(),
    val suggestions: List<Suggestion> = emptyList(),
    val saved: List<OutfitRecord> = emptyList(),
    val generating: Boolean = false,
    /** True once a batch has been asked for, so "none" can differ from "not yet". */
    val hasGenerated: Boolean = false,
    val error: String? = null,
    /**
     * The saved outfit being asked about, if any.
     *
     * The outfit rather than its id, so the prompt can name it -- "delete
     * this?" next to a list of several is a question nobody should have to
     * answer from position alone.
     */
    val deleting: OutfitRecord? = null,
    /**
     * The garment every suggestion is being built around, if any.
     *
     * The record rather than its id, so the screen can name and show the
     * garment it is working from -- "building around something" is not an
     * answer anybody can act on.
     */
    val seed: GarmentRecord? = null,
    /**
     * The rated outfit being asked about, if any.
     *
     * A rating is already recorded and already learned from by the time this
     * appears -- what is being asked is only whether to keep the outfit in the
     * list of things to wear.
     */
    val keeping: Suggestion? = null,
    /** Whether the rated-only outfits are being shown alongside the kept ones. */
    val showingArchived: Boolean = false,
    /** How many are put away, so the toggle can say whether it is worth tapping. */
    val archivedCount: Long = 0,
) {
    /**
     * A suggestion with the id it will be saved under.
     *
     * Minted when the batch is produced rather than when it is saved, which is
     * what makes saving idempotent: tapping "save" and rating it -- which saves
     * it first -- are the same request, and the second one writes nothing.
     */
    @Serializable
    data class Suggestion(
        val id: String,
        val outfit: SuggestedOutfit,
        /** The rating given in this session, if any. */
        val rating: Int? = null,
        val saved: Boolean = false,
    )
}
