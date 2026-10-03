package com.wardrobapp.presentation

/**
 * What the garment detail screen is given to draw.
 *
 * Was `GarmentDetailViewModel.State` in :app. It moved here unchanged when the
 * screens started moving to Compose Multiplatform: a screen's state is what a
 * shared screen takes, so it has to be common code, and here it is compiled
 * and tested on every machine rather than only where there is an Android SDK.
 * The ViewModel that fills it stays in :app.
 *
 * One thing did change: what to say when an error says nothing was an
 * Android resource id, and is an [ErrorFallback] reason now.
 */

data class GarmentDetailScreenState(
    val garmentId: String = "",
    val loading: Boolean = true,
    val view: GarmentDetailView? = null,
    /**
     * Set when the garment is not in the wardrobe -- deleted, or a link to
     * one that never existed. Distinct from an error: there is nothing to
     * retry.
     */
    val missing: Boolean = false,
    /** Set when the read failed, which is not the same as finding nothing. */
    val error: String? = null,
    /** True while a write is in flight, so the actions cannot be double-tapped. */
    val working: Boolean = false,
    /** Non-null while a destructive action is being confirmed. */
    val confirming: Confirm? = null,
    /**
     * Set once the garment is gone, so the screen showing it can leave.
     *
     * Distinct from [missing], which means it was never found. This one says
     * *this* screen deleted it, which is the difference between "that garment
     * does not exist" and closing quietly on the wardrobe behind.
     */
    val deleted: Boolean = false,
    /** Set when an action failed. The read is fine; the write was not. */
    /** What the exception said, which is not translated and may be null. */
    val actionError: String? = null,
    /**
     * What the app was doing, for when the exception says nothing useful.
     *
     * A reason rather than a sentence -- see [ErrorFallback]: the model has no
     * Context, and the screen is where the reader's language is known.
     */
    val actionErrorFallback: ErrorFallback? = null,
) {
    /**
     * The two actions worth asking about first.
     *
     * Retiring is reversible and would not need a prompt on its own, but it is
     * what the React Native app asks about, and it does change what the wardrobe
     * shows. Deleting is not reversible at all.
     */
    enum class Confirm { RETIRE, DELETE }
}
