package com.wardrobapp.app

import android.content.Context
import com.wardrobapp.data.StagedDatabaseCheck
import com.wardrobapp.data.checkWardrobeDatabase
import java.io.File

/**
 * Opens a staged database through the platform's SQLite so it can be checked.
 *
 * The only part of the restore that needs Android: what to check is decided in
 * :data, by [checkWardrobeDatabase], and tested there against real files. This
 * supplies the connection and nothing else.
 *
 * Opening leaves `-wal` and `-shm` beside the file, which is why the restore
 * deletes them before installing it -- a log belonging to a database that was
 * never live has no business being replayed onto the one that is.
 */
class AndroidStagedDatabaseCheck(private val context: Context) : StagedDatabaseCheck {
    override fun check(file: File) {
        AndroidSqlDriver.open(context, file.absolutePath).use { checkWardrobeDatabase(it) }
    }
}
