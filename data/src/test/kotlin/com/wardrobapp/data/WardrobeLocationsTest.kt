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

    @Test
    fun `the database is the one expo-sqlite opens by bare name`() {
        assertEquals(
            "/data/user/0/com.anonymous.wardrobapp/files/SQLite/wardrobapp.db",
            wardrobeFilesIn(filesDir).databaseFile.path,
        )
    }

    @Test
    fun `it is not the directory Context getDatabasePath would resolve to`() {
        // The whole point of this file. `databases/` is the obvious Android
        // answer and the wrong one -- it is a sibling of `files/`, not inside it.
        assertEquals(
            false,
            wardrobeFilesIn(filesDir).databaseFile.path.contains("/databases/"),
        )
    }

    @Test
    fun `photos are where both apps already agree they are`() {
        assertEquals(
            "/data/user/0/com.anonymous.wardrobapp/files/garment-images",
            wardrobeFilesIn(filesDir).imagesDir.path,
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
