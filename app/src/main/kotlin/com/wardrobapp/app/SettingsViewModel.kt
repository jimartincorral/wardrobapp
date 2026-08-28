package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.ArchivePreview
import com.wardrobapp.data.UnrestorableArchiveException
import com.wardrobapp.data.UnrestorableReason
import com.wardrobapp.data.readArchivePreview
import com.wardrobapp.presentation.BackupPhase
import com.wardrobapp.presentation.SettingsView
import com.wardrobapp.presentation.backupPercent
import com.wardrobapp.presentation.formatMegabytes
import com.wardrobapp.presentation.settingsView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Settings: what the wardrobe is using, and moving it in and out.
 *
 * The two halves are deliberately here together. Restoring used to live on the
 * wardrobe screen, which meant the app could take a backup in but not write one
 * out -- so anything done in this app had no way back to the one that ships.
 *
 * Neither half decides anything: what the numbers read as and how full the
 * progress bar is come from :presentation, and the archive itself from :data.
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    data class State(
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
    )

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

    /** Where a backup has got to. */
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

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    /**
     * Shrink what is oversized, delete what nothing points at.
     *
     * Safe to run whenever: a file already small enough is skipped, a file something
     * references is never touched, and anything written in the last hour is left
     * alone -- so a second run over the same wardrobe finds nothing to do and says
     * so. No file is renamed, so no row is touched and the wardrobe is readable
     * throughout.
     */
    fun onTidyRequested() {
        if (_state.value.tidy is Tidy.Running) return

        _state.update { it.copy(tidy = Tidy.Running(done = 0, total = 0)) }

        viewModelScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    container.tidyPhotos { done, total ->
                        _state.update { it.copy(tidy = Tidy.Running(done, total)) }
                    }
                }

                _state.update {
                    it.copy(
                        tidy = if (!summary.changedAnything) {
                            Tidy.NothingToDo(summary.examined)
                        } else {
                            Tidy.Done(
                                tidied = summary.shrunk + summary.deleted,
                                reclaimed = summary.deleted,
                                megabytes = formatMegabytes(summary.bytesSaved),
                            )
                        },
                    )
                }

                // The storage figures above this button are now wrong, which is the
                // point of having pressed it.
                reload()
            } catch (e: Exception) {
                _state.update {
                    it.copy(tidy = Tidy.Failed(e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    fun onTidyDismissed() = _state.update { it.copy(tidy = null) }

    private suspend fun reload(): Long? {
        _state.update { it.copy(loading = true, error = null) }

        return try {
            val view = withContext(Dispatchers.IO) {
                settingsView(
                    garments = container.garments.availableCount(),
                    retired = container.garments.unavailableCount(),
                    photoBytes = container.photoStorageBytes(),
                )
            }
            _state.update { it.copy(loading = false, view = view, error = null) }
            view.garments
        } catch (e: Exception) {
            _state.update {
                it.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
            }
            null
        }
    }

    // ---- backup -------------------------------------------------------------

    /**
     * Write a backup into the file the user chose.
     *
     * Takes a way to open the destination rather than the destination itself, so
     * nothing here knows about content URIs -- and so the stream is opened on
     * the thread that writes to it. The same seam [onArchivePicked] uses in the
     * other direction.
     */
    fun onBackupDestinationPicked(openDestination: () -> OutputStream) {
        _state.update {
            it.copy(backup = Backup.Running(backupPercent(BackupPhase.STAGING, 0, 0)))
        }

        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    container.backupTo(openDestination) { copied, total ->
                        _state.update { current ->
                            // Only while it is still this backup being reported:
                            // a failure that has already been posted must not be
                            // overwritten by a progress callback behind it.
                            if (current.backup is Backup.Running) {
                                current.copy(
                                    backup = Backup.Running(
                                        backupPercent(BackupPhase.ARCHIVING, copied, total)
                                    )
                                )
                            } else {
                                current
                            }
                        }
                    }
                }
            }

            _state.update {
                it.copy(
                    backup = outcome.fold(
                        onSuccess = { summary ->
                            Backup.Done(
                                megabytes = formatMegabytes(summary.bytes),
                                photos = summary.images,
                                skipped = summary.skipped,
                            )
                        },
                        onFailure = { error ->
                            Backup.Failed(error.message ?: error.javaClass.simpleName)
                        },
                    )
                )
            }
        }
    }

    /** The picker was dismissed, or the report was read. */
    fun onBackupDismissed() {
        _state.update { it.copy(backup = null) }
    }

    // ---- restore ------------------------------------------------------------

    fun onRestoreRequested() {
        _state.update { it.copy(restore = Restore.Confirming) }
    }

    fun onRestoreDismissed() {
        pendingArchive = null
        _state.update { it.copy(restore = null) }
    }

    /**
     * The archive waiting to be confirmed.
     *
     * Cleared on dismissal as well as on use, so backing out of a preview cannot
     * leave a file armed for the next confirmation.
     */
    private var pendingArchive: (() -> InputStream)? = null

    /**
     * Read the chosen archive and say what is in it, without applying it.
     *
     * Takes a way to *open* the archive rather than an open one, and that is what
     * makes this step possible at all: the preview consumes a stream and so does
     * the restore, and a `content://` stream does not rewind. A factory can be
     * called twice.
     *
     * An archive that cannot be restored fails here instead, which is the other
     * half of what this step is for: the same refusal, the same sentence, but
     * arriving before somebody has been told their wardrobe is about to be
     * replaced rather than after.
     */
    fun onArchivePicked(openArchive: () -> InputStream) {
        _state.update { it.copy(restore = Restore.Running) }

        viewModelScope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching { openArchive().use { readArchivePreview(it) } }
            }

            // Outside the update: `update` re-runs its lambda when another writer
            // got there first, and a lambda that also assigns a field would do it
            // twice. Harmless for this assignment, and the wrong habit to keep.
            pendingArchive = read.getOrNull()?.let { openArchive }

            val next = read.fold(
                onSuccess = { preview -> Restore.Previewing(preview) },
                onFailure = { error -> error.asRestoreFailure() },
            )

            _state.update { it.copy(restore = next) }
        }
    }

    /**
     * Apply the archive that was previewed.
     *
     * The opener is kept here rather than carried in the state: a lambda in a data
     * class breaks equality, and a state that is never equal to itself makes a
     * StateFlow emit on every update.
     */
    fun onRestoreConfirmed() {
        val openArchive = pendingArchive ?: return

        _state.update { it.copy(restore = Restore.Running) }

        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { openArchive().use { container.restoreFrom(it) } }
            }

            // Reload either way: a refused archive changes nothing, but the
            // figures on screen were read before the attempt and saying so
            // costs nothing.
            val garments = reload()
            pendingArchive = null

            _state.update {
                it.copy(
                    restore = outcome.fold(
                        onSuccess = { Restore.Done(garments) },
                        onFailure = { error -> error.asRestoreFailure() },
                    )
                )
            }
        }
    }

    /**
     * A thrown thing as something the screen can say.
     *
     * Shared by the preview and the restore because they fail the same way and for
     * the same reasons -- which is the point of the preview calling :data's
     * validation rather than its own.
     */
    private fun Throwable.asRestoreFailure() = Restore.Failed(
        message = message ?: javaClass.simpleName,
        reason = (this as? UnrestorableArchiveException)?.reason,
    )
}
