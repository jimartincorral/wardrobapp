package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord

/**
 * What the outfit edit screen is given to draw.
 *
 * Was `OutfitEditViewModel.State` in :app. It moved here unchanged when the
 * screens started moving to Compose Multiplatform: a screen's state is what a
 * shared screen takes, so it has to be common code, and here it is compiled
 * and tested on every machine rather than only where there is an Android SDK.
 * The ViewModel that fills it stays in :app.
 *
 * One thing did change: what to say when an error says nothing was an
 * Android resource id, and is an [ErrorFallback] reason now.
 */

data class OutfitEditScreenState(
    val edit: OutfitEditState = OutfitEditState(),
    /**
     * The garments that can be picked.
     *
     * Retired garments are left out: an outfit is something to wear, and
     * offering a garment marked unavailable would be offering to build an
     * outfit out of clothes that are gone.
     */
    val garments: List<GarmentRecord> = emptyList(),
    /**
     * What has been typed into the picker's search.
     *
     * Screen state rather than part of [edit]: it narrows what is offered and
     * says nothing about the outfit, so it must not travel to the row.
     */
    val search: String = "",
    val loading: Boolean = true,
    val saving: Boolean = false,
    /** Set once the row is written, so the screen knows to leave. */
    val saved: Boolean = false,
    /** Set when the outfit being edited is not there. */
    val missing: Boolean = false,
    val error: String? = null,
    val errorFallback: ErrorFallback? = null,
)
