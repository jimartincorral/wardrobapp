package com.wardrobapp.app

import android.content.Context
import com.wardrobapp.data.DriveBackup
import com.wardrobapp.data.backupFilename
import com.wardrobapp.data.backupsToPrune
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How many archives are kept in Drive.
 *
 * Enough that a backup taken after the damage was done has not pushed out the one
 * from before it, which is the failure a rolling backup is for. Not a setting,
 * because the number that matters is "more than one" and the rest is storage
 * somebody can clear out themselves -- the folder is theirs and they can see it.
 */
const val KEPT_IN_DRIVE = 5

/**
 * Writing a wardrobe to Drive, once.
 *
 * Extracted because there are now two callers and there must not be two
 * implementations: the button in Settings, and the weekly job that runs when
 * nobody is watching. A schedule that backed up slightly differently from the
 * button would be a second thing to get right, and the half nobody sees is the
 * half that would drift.
 *
 * Everything decided here is about order, and both decisions only matter on a bad
 * day. See the comments where they happen.
 */
class DriveBackupRunner(
    private val context: Context,
    private val container: AppContainer,
    private val drive: AndroidDriveBackups,
) {

    /**
     * Back up, prune, and say what is left.
     *
     * Returns the archives still in the folder afterwards, so a caller with a
     * screen can show them without asking Drive a third time.
     */
    suspend fun backUp(onProgress: (Float) -> Unit = {}): List<DriveBackup> {
        val folder = drive.folderId()
        val name = backupFilename(System.currentTimeMillis())
        val staged = File(context.cacheDir, name)

        try {
            // To the cache first rather than streamed straight at Drive: a
            // resumable upload has to declare its size up front, and a staging file
            // that fails is a file, where a half-sent upload is somebody's backup
            // folder with something broken in it.
            withContext(Dispatchers.IO) {
                container.backupTo(
                    openDestination = { staged.outputStream() },
                    onImageCopied = { _, _ -> },
                )
            }

            drive.upload(staged, name, folder, onProgress)
        } finally {
            withContext(Dispatchers.IO) { staged.delete() }
        }

        // Only after one has arrived. Pruning first would, on a run where the
        // upload then failed, leave fewer backups than there were to begin with --
        // and an unattended run is exactly where nobody would notice.
        val listed = drive.list(folder)
        val pruned = backupsToPrune(listed, KEPT_IN_DRIVE).toSet()

        for (id in pruned) {
            drive.delete(id)
        }

        // What is left is known without asking again: the listing above was taken
        // after the upload, so it already holds the new archive, and pruning only
        // removes ids that came out of it.
        return listed.filterNot { it.id in pruned }
    }
}
