package com.wardrobapp.app

import android.content.Context
import com.wardrobapp.data.DriveBackup
import com.wardrobapp.data.backupFilename
import com.wardrobapp.presentation.BackupRetention
import com.wardrobapp.presentation.backupsToRemove
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
     * [retention] is passed per call rather than held, because it is a setting
     * somebody can change between one run and the next, and a runner built once at
     * startup would go on using whatever it was told at the time.
     *
     * Returns the archives still in the folder afterwards, so a caller with a
     * screen can show them without asking Drive a third time.
     */
    suspend fun backUp(
        retention: BackupRetention,
        onProgress: (Float) -> Unit = {},
    ): List<DriveBackup> {
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

        // Which is where "keep all" is answered: it returns nothing to delete
        // rather than a number meaning nothing, and :data's counting stays the only
        // thing that decides *which* when there is a number.
        val pruned = backupsToRemove(listed, retention).toSet()

        for (id in pruned) {
            drive.delete(id)
        }

        // What is left is known without asking again: the listing above was taken
        // after the upload, so it already holds the new archive, and pruning only
        // removes ids that came out of it.
        return listed.filterNot { it.id in pruned }
    }
}
