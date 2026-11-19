package com.wardrobapp.presentation

/**
 * What the bulk add screen is given to draw, around the garment on screen.
 *
 * Was `BulkAddViewModel.State` in :app, and moved here when the screens started
 * moving to Compose Multiplatform: a screen's state is what a shared screen
 * takes, so it has to be common code.
 *
 * The garment itself is not in it. That is an ordinary garment form, with its
 * own model and its own state -- see BulkAddScreenModel.form -- and what it is
 * doing, saving or cutting out a background, is the form's to say. What is
 * left here is the queue and the one wait that belongs to it.
 */
data class BulkAddScreenState(
    val queue: BulkAddState = BulkAddState(),
    /**
     * Photos still being copied in.
     *
     * Never a reason to block the garment on screen: the first garment is meant
     * to be fillable while the twentieth photo is still being copied, which is
     * the whole difference between a queue and a wait.
     */
    val importing: Boolean = false,
    /**
     * Why the queue is short of what was picked, if it is: a photo that could
     * not be copied in. A reason rather than words -- see [ErrorFallback] -- and
     * never the exception's own, since one sentence covers however many failed.
     */
    val errorFallback: ErrorFallback? = null,
)
