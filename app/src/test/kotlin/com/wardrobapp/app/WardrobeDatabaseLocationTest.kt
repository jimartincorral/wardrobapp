package com.wardrobapp.app

import com.wardrobapp.data.WardrobeSchema
import com.wardrobapp.data.wardrobeFilesIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Where the database file actually lands.
 *
 * This is the test that was missing. The port opened its database by bare name,
 * which Android resolves under `databases/`, while the React Native app keeps one
 * in `files/SQLite/` -- so the two would not have shared a wardrobe even under a
 * shared application id. Every existing test was blind to it: the schema tests run
 * against `jdbc:sqlite::memory:` and would pass whichever file the app opened.
 *
 * So this asserts the one thing only Android can answer -- that an absolute path
 * handed to the platform's SQLite opens exactly that file -- and it is why :app
 * has a test source set at all.
 */
@RunWith(RobolectricTestRunner::class)
class WardrobeDatabaseLocationTest {

    private val context = RuntimeEnvironment.getApplication()

    /**
     * Start from a fresh install every time.
     *
     * Explicitly, rather than relying on the test runner to hand each method its
     * own private directory: two of the assertions below are about what a *first*
     * open does, and they would quietly stop testing that if state carried over.
     */
    @Before
    fun emptyTheDataDirectory() {
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        context.getDatabasePath("wardrobapp.db").parentFile?.deleteRecursively()
    }

    @Test
    fun `it opens the file the React Native app writes`() {
        val expected = File(File(context.filesDir, "SQLite"), "wardrobapp.db")
        assertFalse("nothing should exist before opening", expected.exists())

        openWardrobe()

        assertTrue("expected a database at $expected", expected.isFile)
    }

    @Test
    fun `it creates the containing directory, which a fresh install does not have`() {
        val sqliteDir = File(context.filesDir, "SQLite")
        assertFalse("a fresh install has no SQLite directory", sqliteDir.exists())

        openWardrobe()

        assertTrue(sqliteDir.isDirectory)
    }

    @Test
    fun `it does not leave a second database where getDatabasePath points`() {
        openWardrobe()

        // The trap this whole change exists to close: a bare name would have
        // landed here instead, one directory over from the real wardrobe, and
        // nothing would have complained.
        val bareName = context.getDatabasePath("wardrobapp.db")
        assertFalse("a second database appeared at $bareName", bareName.isFile)
    }

    @Test
    fun `the file it opens is a usable wardrobe`() {
        openWardrobe()

        // Reopened from scratch, so this reads what was written to disk rather
        // than anything the first connection was holding.
        AndroidSqlDriver.open(context, databasePath()).use { driver ->
            val rows = driver.query("SELECT count(*) AS count FROM garments;")
            assertEquals(1, rows.size)
        }
    }

    private fun databasePath(): String =
        wardrobeFilesIn(context.filesDir).databaseFile.absolutePath

    private fun openWardrobe() {
        AndroidSqlDriver.open(context, databasePath()).use { WardrobeSchema.applyTo(it) }
    }
}
