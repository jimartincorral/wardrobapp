package com.wardrobapp.presentation

import com.wardrobapp.data.DuplicateGarment
import com.wardrobapp.domain.ImportFailureReason
import com.wardrobapp.domain.ImportWarning
import com.wardrobapp.domain.UnsafeUrlReason

/**
 * What the garment form screen is given to draw.
 *
 * Was `GarmentFormViewModel.State` in :app. It moved here unchanged when the
 * screens started moving to Compose Multiplatform: a screen's state is what a
 * shared screen takes, so it has to be common code, and here it is compiled
 * and tested on every machine rather than only where there is an Android SDK.
 * The ViewModel that fills it stays in :app.
 *
 * One thing did change: what to say when an error says nothing was an
 * Android resource id, and is an [ErrorFallback] reason now.
 */

data class GarmentFormScreenState(
    val form: GarmentFormState = GarmentFormState().normalized(),
    val brands: List<String> = emptyList(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    /** Set once the garment is written, so the screen knows to leave. */
    val saved: Boolean = false,
    /**
     * Likely duplicates, shown before anything is written. Only ever set
     * when adding: editing a garment cannot make it a duplicate of itself.
     */
    val duplicates: List<DuplicateGarment> = emptyList(),
    /** What the exception said, which is not translated and may be null. */
    val error: String? = null,
    /**
     * What the app was doing, for when the exception says nothing useful.
     *
     * A reason rather than a sentence -- see [ErrorFallback]: the model has no
     * Context, and the screen is where the reader's language is known.
     */
    val errorFallback: ErrorFallback? = null,
    /**
     * What the dialog is titled.
     *
     * Defaults to "Couldn't save", which is what every error on this screen
     * used to be called -- including a failed background removal, a colour
     * that could not be read and a missing camera, none of which are saves.
     * A bug reported as "Couldn't save. That photo could not be opened" is a
     * bug report about the wrong thing, so the ones that are not saves say so.
     */
    val errorTitle: ErrorTitle = ErrorTitle.SAVE,
    /** Set when the garment being edited is not there any more. */
    val missing: Boolean = false,
    /**
     * True while the model is cutting a photo out. Separate from [saving]
     * because it takes seconds rather than milliseconds, and the screen says
     * something different about it.
     */
    val removingBackground: Boolean = false,
    /** Where URL import has got to, if it is anywhere. */
    val urlImport: UrlImport = UrlImport(),
    /**
     * True while a photo's colour is being read.
     *
     * Separate from [saving] like [removingBackground] is, and for the same
     * reason: it is its own wait with its own thing to say about it.
     */
    val detectingColor: Boolean = false,
) {
    /**
     * URL import, as the form sees it.
     *
     * Its own type rather than six more fields on [GarmentFormScreenState]: it is a self-contained
     * side conversation -- paste, confirm, wait, read what happened -- and the rest
     * of the form carries on regardless of where it has got to.
     */
    data class UrlImport(
        /** What has been typed or pasted. */
        val url: String = "",
        /**
         * An address handed over by something else, waiting for a tap.
         *
         * Present means the confirmation is on screen. A deep link or a share can
         * carry an address, and any web page, message or QR code can produce
         * either -- so fetching it unasked would let a page use this app's position
         * inside the user's network to reach whatever it names. The host is shown
         * and nothing is fetched until someone agrees.
         */
        val awaitingConfirmation: String? = null,
        val running: Boolean = false,
        /** The shop an import came from, once one has succeeded. */
        val source: String? = null,
        /** How many photos arrived, for the line under the field. */
        val imported: Int? = null,
        val warnings: List<ImportWarning> = emptyList(),
        val problem: ImportProblem? = null,
    )

    /**
     * Why an import did not happen.
     *
     * Reasons rather than sentences, so the screen can say them in the reader's
     * language -- the same arrangement as the archive failures. [Foreign] is the
     * exception that proves it: words from the network stack, which this app did
     * not write and cannot translate.
     */
    sealed interface ImportProblem {
        data class Unsafe(val reason: UnsafeUrlReason) : ImportProblem
        data class Failed(val reason: ImportFailureReason) : ImportProblem
        data class Foreign(val text: String?) : ImportProblem
    }
}
