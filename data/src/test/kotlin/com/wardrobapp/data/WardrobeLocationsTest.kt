package com.wardrobapp.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where the wardrobe's files are.
 *
 * Worth pinning rather than trusting, because the failure is silent: an app
 * looking in the wrong directory finds nothing, creates an empty database and
 * reports an empty wardrobe. These assertions are written against the paths the
 * React Native app actually uses, so a change here has to be a deliberate one.
 */
class WardrobeLocationsTest {

    private val filesDir = File("/data/user/0/com.anonymous.wardrobapp/files")

    /**
     * The path as the device would write it, whatever the host writes it as.
     *
     * These are locations on an Android phone, which is POSIX, but `File` renders
     * a path with the separator of whatever machine is running the test. On
     * Windows that is a backslash, which made the literal comparisons below fail
     * for no reason -- and, worse, made the `/databases/` check below unable to
     * fail for any reason, since a path full of backslashes never contains it.
     * A test written against a silent failure should not have one of its own.
     */
    private val File.devicePath: String get() = path.replace(File.separatorChar, '/')

    @Test
    fun `the database is the one expo-sqlite opens by bare name`() {
        assertEquals(
            "/data/user/0/com.anonymous.wardrobapp/files/SQLite/wardrobapp.db",
            wardrobeFilesIn(filesDir).databaseFile.devicePath,
        )
    }

    @Test
    fun `it is not the directory Context getDatabasePath would resolve to`() {
        // The whole point of this file. `databases/` is the obvious Android
        // answer and the wrong one -- it is a sibling of `files/`, not inside it.
        assertEquals(
            false,
            wardrobeFilesIn(filesDir).databaseFile.devicePath.contains("/databases/"),
        )
    }

    @Test
    fun `photos are where the wardrobes on real phones already are`() {
        assertEquals(
            "/data/user/0/com.anonymous.wardrobapp/files/garment-images",
            wardrobeFilesIn(filesDir).imagesDir.devicePath,
        )
    }

    @Test
    fun `the database sits beside the photos, not inside them`() {
        val files = wardrobeFilesIn(filesDir)
        assertEquals(files.imagesDir.parent, files.databaseFile.parentFile.parent)
    }

    @Test
    fun `the archive entry name and the live filename are the same string today`() {
        // Separate constants on purpose, so this is a fact about now rather than
        // a constraint. If a backup format renames its entry, this test says
        // which of the two changed.
        assertEquals(ARCHIVE_DB_FILENAME, DATABASE_FILENAME)
    }
}
