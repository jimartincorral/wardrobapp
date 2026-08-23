package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    )

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
     * picking a file is not the same as agreeing to that.
     */
    sealed interface Restore {
        data object Confirming : Restore
        data object Running : Restore
        /** Restored; the count is absent only if the reload afterwards failed. */
        data class Done(val garments: Long?) : Restore
        data class Failed(val message: String) : Restore
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

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
        _state.update { it.copy(restore = null) }
    }

    /**
     * Restore from an archive, then show what happened.
     *
     * Takes a way to open the archive rather than the archive itself, for the
     * same reasons as [onBackupDestinationPicked].
     */
    fun onArchivePicked(openArchive: () -> InputStream) {
        _state.update { it.copy(restore = Restore.Running) }

        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { openArchive().use { container.restoreFrom(it) } }
            }

            // Reload either way: a refused archive changes nothing, but the
            // figures on screen were read before the attempt and saying so
            // costs nothing.
            val garments = reload()

            _state.update {
                it.copy(
                    restore = outcome.fold(
                        onSuccess = { Restore.Done(garments) },
                        onFailure = { error ->
                            Restore.Failed(error.message ?: error.javaClass.simpleName)
                        },
                    )
                )
            }
        }
    }
}
