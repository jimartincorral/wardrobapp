package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.OutfitRecord

/**
 * What the outfit detail screen is given to draw.
 *
 * Was `OutfitDetailViewModel.State` in :app. It moved here unchanged when the
 * screens started moving to Compose Multiplatform: a screen's state is what a
 * shared screen takes, so it has to be common code, and here it is compiled
 * and tested on every machine rather than only where there is an Android SDK.
 * The ViewModel that fills it stays in :app.
 *
 * One thing did change: what to say when an error says nothing was an
 * Android resource id, and is an [ErrorFallback] reason now.
 */

data class OutfitDetailScreenState(
    val loading: Boolean = true,
    val outfit: OutfitRecord? = null,
    /**
     * The garments in it, in the order the outfit lists them.
     *
     * One that has since been deleted is simply absent: `GarmentWrites.delete`
     * drops a garment from every outfit it belongs to, so this only happens
     * for a row that predates that or came from a restored backup.
     */
    val garments: List<GarmentRecord> = emptyList(),
    /**
     * What the rating adds up to.
     *
     * Over at most one rating, because rating an outfit replaces any previous
     * one. So this is really "the rating", with the clamping
     * and the star rounding that a value from a restored backup needs.
     */
    val rating: RatingSummary = ratingSummary(emptyList()),
    /** Set when the outfit is not there -- deleted, or a link to nothing. */
    val missing: Boolean = false,
    /** What the exception said, which is not translated and may be null. */
    val error: String? = null,
    /**
     * What the app was doing when it failed, for when the exception says
     * nothing useful -- which is the case this used to cover with an English
     * sentence written into the model.
     *
     * A reason rather than a string -- see [ErrorFallback] -- because the model
     * has no Context and should not acquire one for this: the screen is where
     * the reader's language is known.
     */
    val errorFallback: ErrorFallback? = null,
    val working: Boolean = false,
    val confirmingDelete: Boolean = false,
    /** Set once it is gone, so the screen showing it can leave. */
    val deleted: Boolean = false,
)
