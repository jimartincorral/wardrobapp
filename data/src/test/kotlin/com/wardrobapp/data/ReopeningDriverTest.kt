package com.wardrobapp.data

import java.io.File
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The connection that can be put down while its file is replaced.
 *
 * Run against a real database file rather than an in-memory one, because the
 * whole question is what happens when the *file* changes underneath an open
 * connection -- which an in-memory database cannot express.
 */
class ReopeningDriverTest {

    private val directory: File = File.createTempFile("reopening", "").let {
        it.delete(); it.mkdirs(); it
    }

    private val databaseFile = File(directory, "wardrobapp.db")

    @AfterTest
    fun cleanup() {
        directory.deleteRecursively()
    }

    /** Records that it was closed, which is the contract being checked. */
    private class Recording(private val delegate: JdbcSqlDriver) : CloseableSqlDriver {
        var closed = false
            private set

        override fun query(sql: String, args: List<Any?>) = delegate.query(sql, args)
        override fun execute(sql: String, args: List<Any?>) = delegate.execute(sql, args)
        override fun <T> transaction(block: () -> T): T = delegate.transaction(block)

        override fun close() {
            closed = true
            delegate.close()
        }
    }

    private val opened = mutableListOf<Recording>()
    private val opens: Int get() = opened.size

    private val driver = ReopeningDriver {
        Recording(
            JdbcSqlDriver(DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}"))
                .also { WardrobeSchema.applyTo(it) }
        ).also { opened.add(it) }
    }

    private fun writeWardrobe(target: File, garmentId: String) {
        JdbcSqlDriver(DriverManager.getConnection("jdbc:sqlite:${target.absolutePath}")).use {
            WardrobeSchema.applyTo(it)
            it.execute(
                "INSERT INTO garments (id, image_uri, category, created_at, updated_at) " +
                    "VALUES (?, ?, 'tops', '2026-01-01', '2026-01-01')",
                listOf(garmentId, "$garmentId.jpg"),
            )
        }
    }

    private fun garmentIds() = driver.query("SELECT id FROM garments ORDER BY id")
        .map { it["id"] as String }

    @Test
    fun `opens nothing until something is asked`() {
        assertEquals(0, opens, "a database was opened before anything needed it")
        assertTrue(!databaseFile.exists())

        garmentIds()

        assertEquals(1, opens)
    }

    @Test
    fun `reuses the connection it already has`() {
        garmentIds()
        garmentIds()
        driver.execute("DELETE FROM garments WHERE id = 'nobody'")

        assertEquals(1, opens)
    }

    @Test
    fun `sees a file replaced while it was closed`() {
        // The reason this class exists. A connection held across a restore keeps
        // answering from the database it opened -- the one that was just thrown
        // away -- so the app would show the old wardrobe until it was killed.
        writeWardrobe(databaseFile, "before")
        assertEquals(listOf("before"), garmentIds())

        driver.whileClosed {
            val replacement = File(directory, "replacement.db")
            writeWardrobe(replacement, "after")
            databaseFile.delete()
            assertTrue(replacement.renameTo(databaseFile))
        }

        assertEquals(listOf("after"), garmentIds())
        assertEquals(2, opens, "the connection was not reopened")
    }

    @Test
    fun `refuses to answer while the database is being replaced`() {
        // Reopening mid-restore would answer from the file about to be replaced,
        // or from a half-installed one. An error is the honest response, and the
        // only caller is a UI that is showing a progress dialog anyway.
        writeWardrobe(databaseFile, "before")
        garmentIds()

        driver.whileClosed {
            assertFailsWith<IllegalStateException> { garmentIds() }
            assertFailsWith<IllegalStateException> { driver.execute("DELETE FROM garments") }
            assertFailsWith<IllegalStateException> { driver.transaction { garmentIds() } }
        }

        assertEquals(listOf("before"), garmentIds())
    }

    @Test
    fun `opens again after a restore that failed`() {
        // A refused archive leaves the wardrobe alone, so the app has to go back
        // to reading it -- the window has to close even when the work inside
        // threw.
        writeWardrobe(databaseFile, "before")
        garmentIds()

        assertFailsWith<UnrestorableArchiveException> {
            driver.whileClosed {
                throw UnrestorableArchiveException(UnrestorableReason.NoDatabase)
            }
        }

        assertEquals(listOf("before"), garmentIds())
    }

    @Test
    fun `closes the connection before the block runs, not after`() {
        // Nothing can replace a database SQLite still has open on every
        // filesystem the app might be installed on, so the close has to have
        // already happened by the time the restore starts -- not be tidied up
        // afterwards.
        writeWardrobe(databaseFile, "before")
        garmentIds()
        var closedInsideWindow: Boolean? = null

        driver.whileClosed { closedInsideWindow = opened.single().closed }

        assertEquals(true, closedInsideWindow, "the old connection was still open")
        garmentIds()
        assertEquals(2, opens)
        assertEquals(false, opened.last().closed, "the new connection was closed too")
    }
}
