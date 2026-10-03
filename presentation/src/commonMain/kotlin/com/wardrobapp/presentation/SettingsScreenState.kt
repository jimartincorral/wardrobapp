package com.wardrobapp.presentation

import com.wardrobapp.data.ArchivePreview
import com.wardrobapp.data.UnrestorableReason

/**
 * What the settings screen is given to draw.
 *
 * Was `SettingsViewModel.State` in :app. It moved here unchanged when the
 * screens started moving to Compose Multiplatform: a screen's state is what a
 * shared screen takes, so it has to be common code, and here it is compiled
 * and tested on every machine rather than only where there is an Android SDK.
 * The ViewModel that fills it stays in :app.
 */

data class SettingsScreenState(
    val loading: Boolean = true,
    val view: SettingsView? = null,
    /** Set when the figures could not be read; shown, not swallowed. */
    val error: String? = null,
    /** Non-null while a backup is running or has something to report. */
    val backup: Backup? = null,
    /** Non-null while a restore is being asked about, run, or reported. */
    val restore: Restore? = null,
    /** Non-null while the photo tidy-up is running or has something to say. */
    val tidy: Tidy? = null,
) {
    /**
     * Where the photo tidy-up has got to.
     *
     * Its own three states rather than a boolean and a message, so "nothing needed
     * doing" is a distinct answer from "here is what was saved". They read
     * differently and they mean differently: the first says the wardrobe is already
     * as small as this app can make it.
     */
    sealed interface Tidy {
        data class Running(val done: Int, val total: Int) : Tidy
        data class NothingToDo(val examined: Int) : Tidy
        /**
         * What a pass came to.
         *
         * [tidied] is both passes together, because a reader pressed one button and
         * a dialog reporting two numbers for one press reads as two things having
         * happened. [reclaimed] is carried separately only so the dialog can say
         * that files were deleted, which is the part worth knowing.
         */
        data class Done(val tidied: Int, val reclaimed: Int, val megabytes: String) : Tidy
        data class Failed(val message: String) : Tidy
    }

    sealed interface Backup {
        data class Running(val percent: Int) : Backup

        data class Done(val megabytes: String, val photos: Int, val skipped: Int) : Backup

        data class Failed(val message: String) : Backup
    }

    /**
     * Where a restore has got to.
     *
     * [Confirming] exists because a restore replaces the whole wardrobe, and
     * picking a file is not the same as agreeing to that. [Previewing] exists
     * because agreeing to it in the abstract is not the same as agreeing to *this
     * archive*: until the file is chosen there is nothing to describe, and after
     * it is chosen there is no reason not to.
     */
    sealed interface Restore {
        data object Confirming : Restore

        /**
         * A chosen archive, read but not applied.
         *
         * The wardrobe is untouched here: [readArchivePreview] writes nothing and
         * refuses whatever the restore would refuse, so an archive that reaches
         * this state is one the restore has already agreed to accept.
         */
        data class Previewing(val preview: ArchivePreview) : Restore

        data object Running : Restore
        /** Restored; the count is absent only if the reload afterwards failed. */
        data class Done(val garments: Long?) : Restore
        /**
         * Could not restore.
         *
         * [reason] is present when :data recognised the failure, which is every
         * unusable archive; [message] is its English sentence, kept for anything
         * that is not one -- a filesystem error, say -- where there is nothing but
         * the exception's own words.
         */
        data class Failed(
            val message: String,
            val reason: UnrestorableReason? = null,
        ) : Restore
    }
}
