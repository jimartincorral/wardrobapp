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
 * These run on `viewModelScope`, which dispatches on the main thread. Nothing here
 * may block it, and nothing here has to: each collaborator moves its own work off,
 * which is the only arrangement that does not depend on every future caller
 * remembering to.
 *
 * An [AuthorizationService] holds a browser binding, so it is created once and
 * closed in [onCleared]; leaking one leaks the connection behind it.
 */
class CloudBackupViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.get(application)
    private val auth = DriveAuth(application)
    private val service = AuthorizationService(application)

    private val drive = AndroidDriveBackups(application) { auth.accessToken(service) }

    private val runner = DriveBackupRunner(application, container, drive)

    private val schedule = BackupSchedule(application)

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
        /**
         * Set once a restore has finished, so the screen can say so.
         *
         * A restore replaces the wardrobe without any visible sign of it: the
         * spinner stops and the section looks exactly as it did. The local restore
         * confirms itself with a dialog, and arriving by way of Drive is not a
         * reason to say less.
         */
        val restored: Boolean = false,
    )

    /** The one thing in flight, since none of these may overlap. */
    enum class Working { CONNECTING, LISTING, BACKING_UP, RESTORING }

    private val _state = MutableStateFlow(
        State(
            signedIn = auth.isSignedIn,
            scheduled = schedule.enabled,
            lastRunAt = schedule.lastRunAt,
            lastRunFailure = schedule.lastFailure,
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        if (auth.isSignedIn) refresh()
    }

    /**
     * The sign-in screen to launch, or null when this phone cannot show one.
     *
     * Null rather than an exception because of where this is called from: the tap
     * handler, outside the coroutine that catches everything else here. AppAuth
     * throws a bare `ActivityNotFoundException` when it cannot find a browser to
     * hand the sign-in to, and an exception thrown there closes the app rather than
     * reaching a dialog.
     *
     * That it can happen at all is a manifest matter -- see the `<queries>` block,
     * without which Android reports no browsers on a phone that plainly has one --
     * but a phone with genuinely no browser is a real phone, and it should be told
     * so rather than shut down.
     */
    fun authorizationIntent(): Intent? = try {
        auth.authorizationIntent(service)
    } catch (error: Exception) {
        // The exception goes on screen and the sentence names no cause. Two earlier
        // versions of this blamed a missing browser, which was wrong: the failure
        // was an `IllegalArgumentException` from building the request, thrown
        // before a browser was ever looked for. Saying only what is known is what
        // found it.
        _state.update {
            it.copy(
                failure = getApplication<Application>().getString(
                    R.string.error_drive_signin_unavailable,
                    listOfNotNull(error.javaClass.simpleName, error.message)
                        .joinToString(": "),
                ),
            )
        }
        null
    }

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
        val kept = runner.backUp { sent -> _state.update { it.copy(progress = sent) } }

        // A backup is a backup: the line on screen says when this wardrobe last
        // reached Drive, and it would be a strange reading of that to count only
        // the ones nobody asked for.
        schedule.recordSuccess(System.currentTimeMillis())

        _state.update {
            it.copy(backups = kept, lastRunAt = schedule.lastRunAt, lastRunFailure = null)
        }
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
            withContext(Dispatchers.IO) { downloaded.delete() }
        }
    }

    /** Turn the weekly backup on or off. */
    fun onScheduleChanged(wanted: Boolean) {
        if (wanted) schedule.enable() else schedule.disable()
        _state.update { it.copy(scheduled = schedule.enabled) }
    }

    /**
     * Forget the permission.
     *
     * Nothing in Drive is touched. The archives stay where their owner can see
     * them, which is the whole reason for `drive.file` over a hidden folder.
     *
     * The schedule goes with it, and that is not tidiness: a weekly job with no
     * permission left would wake, fail to get a token, retry, and go on doing that
     * for as long as the app is installed, with nobody watching and nothing said.
     * Reconnecting does not turn it back on -- switching something off on the way
     * out and finding it on when you return would be the app deciding for you.
     */
    fun onSignOutRequested() {
        schedule.disable()
        auth.signOut()
        _state.update { State(signedIn = false) }
    }

    fun onFailureDismissed() = _state.update { it.copy(failure = null) }

    fun onRestoredDismissed() = _state.update { it.copy(restored = false) }

    /**
     * Ask what is in Drive, without putting anything there.
     *
     * [AndroidDriveBackups.existingFolderId] rather than `folderId`: this runs on
     * opening the screen, and a question about what is in somebody's Drive must
     * not be the thing that creates a folder in it. No folder means no backups,
     * which is exactly what the empty list says.
     */
    private suspend fun loadList() {
        val folder = drive.existingFolderId()
        val backups = folder?.let { drive.list(it) } ?: emptyList()
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
