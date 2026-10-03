package com.wardrobapp.presentation

import com.wardrobapp.data.ArchivePreview
import com.wardrobapp.data.BackupSummary
import com.wardrobapp.data.MaintenanceSummary
import com.wardrobapp.data.UnrestorableArchiveException
import com.wardrobapp.presentation.SettingsScreenState.Backup
import com.wardrobapp.presentation.SettingsScreenState.Restore
import com.wardrobapp.presentation.SettingsScreenState.Tidy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** What the storage section reports, as read. */
@Serializable
data class StorageFigures(val garments: Long, val retired: Long, val photoBytes: Long)

/**
 * Where the settings screen's figures come from, and what does its maintenance.
 *
 * Generic over what a chosen archive and a chosen backup destination *are*,
 * because that is the one thing the platforms do entirely differently. On the
 * phone each is a way to open a stream on what the system picker returned --
 * the picker hands over a `content://` URI, and a stream on one does not
 * rewind, which is why an opener and not an open stream: the preview reads the
 * archive and then the restore reads it again. In the browser they will be an
 * upload and a download. The model never looks inside either; it holds an
 * archive between preview and confirmation, and passes both through.
 */
interface SettingsSource<Archive, Destination> {
    suspend fun storage(): StorageFigures

    /**
     * Shrink what is oversized, delete what nothing points at, reporting each
     * photo examined. See SettingsScreenModel.onTidyRequested.
     */
    suspend fun tidy(onProgress: (done: Int, total: Int) -> Unit): MaintenanceSummary

    /** Write a backup to [destination], reporting each photo copied. */
    suspend fun backup(destination: Destination, onProgress: (copied: Int, total: Int) -> Unit): BackupSummary

    /**
     * What [archive] holds, without applying it -- and the same refusal the
     * restore would give, if it cannot be restored, so it arrives before
     * somebody is told their wardrobe is about to be replaced.
     */
    suspend fun preview(archive: Archive): ArchivePreview

    /** Replace the wardrobe with [archive]'s, and its settings too if asked. */
    suspend fun restore(archive: Archive, withSettings: Boolean)
}

/** The settings screen: what SettingsViewModel did, in common code. See ScreenModels.kt. */
class SettingsScreenModel<Archive, Destination>(
    private val scope: CoroutineScope,
    private val source: SettingsSource<Archive, Destination>,
) {
    private val _state = MutableStateFlow(SettingsScreenState())
    val state: StateFlow<SettingsScreenState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        scope.launch { reload() }
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

        scope.launch {
            attempt {
                source.tidy { done, total -> _state.update { it.copy(tidy = Tidy.Running(done, total)) } }
            }
                .onSuccess { summary ->
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

                    // The storage figures above this button are now wrong, which is
                    // the point of having pressed it.
                    reload()
                }
                .onFailure { e -> _state.update { it.copy(tidy = Tidy.Failed(e.readableMessage())) } }
        }
    }

    fun onTidyDismissed() = _state.update { it.copy(tidy = null) }

    /** Re-read the figures; the garment count, or null if they could not be read. */
    private suspend fun reload(): Long? {
        _state.update { it.copy(loading = true, error = null) }

        return attempt { source.storage() }.fold(
            onSuccess = { figures ->
                val view = settingsView(figures.garments, figures.retired, figures.photoBytes)
                _state.update { it.copy(loading = false, view = view, error = null) }
                view.garments
            },
            onFailure = { e ->
                _state.update { it.copy(loading = false, error = e.readableMessage()) }
                null
            },
        )
    }

    // ---- backup -------------------------------------------------------------

    /** Write a backup to the destination the reader chose. */
    fun onBackupDestinationPicked(destination: Destination) {
        _state.update {
            it.copy(backup = Backup.Running(backupPercent(BackupPhase.STAGING, 0, 0)))
        }

        scope.launch {
            val outcome = attempt {
                source.backup(destination) { copied, total ->
                    _state.update { current ->
                        // Only while it is still this backup being reported: a
                        // failure that has already been posted must not be
                        // overwritten by a progress callback behind it.
                        if (current.backup is Backup.Running) {
                            current.copy(backup = Backup.Running(backupPercent(BackupPhase.ARCHIVING, copied, total)))
                        } else {
                            current
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
                        onFailure = { error -> Backup.Failed(error.readableMessage()) },
                    ),
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
     * leave a file armed for the next confirmation. Kept here rather than carried
     * in the state: on the phone it is a lambda, and a lambda in a data class
     * breaks equality -- a state that is never equal to itself makes a StateFlow
     * emit on every update.
     */
    private var pendingArchive: Archive? = null

    /**
     * Read the chosen archive and say what is in it, without applying it.
     *
     * An archive that cannot be restored fails here instead, which is the other
     * half of what this step is for: the same refusal, the same sentence, but
     * arriving before somebody has been told their wardrobe is about to be
     * replaced rather than after.
     */
    fun onArchivePicked(archive: Archive) {
        _state.update { it.copy(restore = Restore.Running) }

        scope.launch {
            val read = attempt { source.preview(archive) }

            // Outside the update: `update` re-runs its lambda when another writer
            // got there first, and a lambda that also assigns a field would do it
            // twice. Harmless for this assignment, and the wrong habit to keep.
            pendingArchive = if (read.isSuccess) archive else null

            val next = read.fold(
                onSuccess = { preview -> Restore.Previewing(preview) },
                onFailure = { error -> error.asRestoreFailure() },
            )

            _state.update { it.copy(restore = next) }
        }
    }

    /** Apply the archive that was previewed. */
    fun onRestoreConfirmed(withSettings: Boolean) {
        val archive = pendingArchive ?: return

        _state.update { it.copy(restore = Restore.Running) }

        scope.launch {
            val outcome = attempt { source.restore(archive, withSettings) }

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
                    ),
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
        message = readableMessage(),
        reason = (this as? UnrestorableArchiveException)?.reason,
    )
}
