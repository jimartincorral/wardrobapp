package com.wardrobapp.presentation

/**
 * What the bulk add screen is given to draw.
 *
 * Was `BulkAddViewModel.State` in :app. It moved here unchanged when the
 * screens started moving to Compose Multiplatform: a screen's state is what a
 * shared screen takes, so it has to be common code, and here it is compiled
 * and tested on every machine rather than only where there is an Android SDK.
 * The ViewModel that fills it stays in :app.
 *
 * One thing did change: what to say when an error says nothing was an
 * Android resource id, and is an [ErrorFallback] reason now.
 */

data class BulkAddScreenState(
    val queue: BulkAddState = BulkAddState(),
    /**
     * Photos still being copied in.
     *
     * Kept apart from [saving] because they mean opposite things for the
     * buttons: a garment cannot be confirmed twice, so writing one disables
     * them, but a batch still arriving must not -- the first garment is meant
     * to be fillable while the twentieth photo is still being copied, which is
     * the whole difference between a queue and a wait.
     */
    val importing: Boolean = false,
    /** A garment being written. */
    val saving: Boolean = false,
    /** A background being cut out of the garment on screen. */
    val removingBackground: Boolean = false,
    val error: String? = null,
    val errorFallback: ErrorFallback? = null,
)
