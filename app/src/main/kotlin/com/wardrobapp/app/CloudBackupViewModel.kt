package com.wardrobapp.app

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.DriveBackup
import com.wardrobapp.data.backupFilename
import com.wardrobapp.data.backupsToPrune
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationService

/**
 * How many archives are kept in Drive.
 *
 * Enough that a backup taken after the damage was done has not pushed out the one
 * from before it, which is the failure a rolling backup is for. Not a setting,
 * because the number that matters is "more than one" and the rest is storage
 * somebody can clear out themselves -- the folder is theirs and they can see it.
 */
private const val KEPT_IN_DRIVE = 5

/**
 * Backing a wardrobe up to somebody's Google Drive, and getting it back.
 *
 * Everything worth deciding is decided elsewhere: :data says which archives are
 * ours and which to prune, [DriveAuth] holds the permission, [AndroidDriveBackups]
 * moves the bytes, and [AppContainer] writes and reads the archive itself. What is
 * left here is the order they happen in and what the screen is told.
 *
 * An [AuthorizationService] holds a browser binding, so it is created once and
 * closed in [onCleared]; leaking one leaks the connection behind it.
 */
class CloudBackupViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.get(application)
    private val auth = DriveAuth(application)
    private val service = AuthorizationService(application)

    private val drive = AndroidDriveBackups(application) { auth.accessToken(service) }

    data class State(
        val signedIn: Boolean = false,
        /** What is in Drive, newest first. Empty until it has been asked for. */
        val backups: List<DriveBackup> = emptyList(),
        /** What is happening, or null when nothing is. */
        val working: Working? = null,
        /** 0..1 where there is something true to draw, null while there is not. */
        val progress: Float? = null,
        /** What went wrong, in the words of whatever failed. */
        val failure: String? = null,
        /** Set once a restore has finished, so the screen can say the app must be reopened. */
        val restored: Boolean = false,
    )

    /** The one thing in flight, since none of these may overlap. */
    enum class Working { CONNECTING, LISTING, BACKING_UP, RESTORING }

    private val _state = MutableStateFlow(State(signedIn = auth.isSignedIn))
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        if (auth.isSignedIn) refresh()
    }

    /** The sign-in screen to launch, or null when there is already permission. */
    fun authorizationIntent(): Intent = auth.authorizationIntent(service)

    /** What came back from it. */
    fun onAuthorizationResult(data: Intent?) {
        if (data == null) return

        run(Working.CONNECTING) {
            auth.completeAuthorization(data, service)
            _state.update { it.copy(signedIn = true) }
            loadList()
        }
    }

    /** Ask Drive what is there. */
    fun refresh() = run(Working.LISTING) { loadList() }

    /**
     * Write a backup and send it up.
     *
     * The archive is written to the cache first rather than streamed straight at
     * Drive: the size has to be known before the upload starts, and a staging file
     * that fails is a file, while a half-sent upload is somebody's backup folder
     * with something broken in it.
     */
    fun onBackUpRequested() = run(Working.BACKING_UP) {
        val folder = drive.folderId()
        val name = backupFilename(System.currentTimeMillis())
        val staged = File(getApplication<Application>().cacheDir, name)

        try {
            withContext(Dispatchers.IO) {
                container.backupTo(
                    openDestination = { staged.outputStream() },
                    onImageCopied = { _, _ -> },
                )
            }

            drive.upload(staged, name, folder) { sent ->
                _state.update { it.copy(progress = sent) }
            }
        } finally {
            staged.delete()
        }

        // Only after one has arrived. Pruning first would, on a run where the
        // upload then failed, leave fewer backups than there were to begin with.
        for (id in backupsToPrune(drive.list(folder), KEPT_IN_DRIVE)) {
            drive.delete(id)
        }

        loadList()
    }

    /**
     * Bring one back down and restore it.
     *
     * The download lands in the cache and is handed to the same restore the
     * document picker uses, which stages, verifies and rolls back -- so an archive
     * that arrives damaged replaces nothing.
     */
    fun onRestoreRequested(backup: DriveBackup) = run(Working.RESTORING) {
        val downloaded = File(getApplication<Application>().cacheDir, backup.name)

        try {
            drive.download(backup.id, downloaded) { received ->
                _state.update { it.copy(progress = received) }
            }

            withContext(Dispatchers.IO) {
                downloaded.inputStream().use { container.restoreFrom(it) }
            }

            _state.update { it.copy(restored = true) }
        } finally {
            downloaded.delete()
        }
    }

    /**
     * Forget the permission.
     *
     * Nothing in Drive is touched. The archives stay where their owner can see
     * them, which is the whole reason for `drive.file` over a hidden folder.
     */
    fun onSignOutRequested() {
        auth.signOut()
        _state.update { State(signedIn = false) }
    }

    fun onFailureDismissed() = _state.update { it.copy(failure = null) }

    private suspend fun loadList() {
        val backups = drive.list(drive.folderId())
        _state.update { it.copy(backups = backups) }
    }

    /**
     * One job at a time, with whatever it threw put on the screen.
     *
     * Every one of these ends in the same three ways -- finished, refused by
     * Drive, or the connection gave out -- and the screen says the same thing
     * about the last two, so there is one place that catches them.
     */
    private fun run(working: Working, block: suspend () -> Unit) {
        if (_state.value.working != null) return

        _state.update { it.copy(working = working, progress = null, failure = null) }

        viewModelScope.launch {
            try {
                block()
                _state.update { it.copy(working = null, progress = null) }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        working = null,
                        progress = null,
                        failure = error.message ?: error.javaClass.simpleName,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        service.dispose()
    }
}
