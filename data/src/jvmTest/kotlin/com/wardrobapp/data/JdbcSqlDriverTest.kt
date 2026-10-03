package com.wardrobapp.data

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JdbcSqlDriverTest {

    private val directory: File = Files.createTempDirectory("jdbc-driver").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `open creates the file and the directory it goes in`() {
        val file = File(directory, "nested/deeper/wardrobe.db")

        JdbcSqlDriver.open(file).use { it.execute("CREATE TABLE t (x TEXT);") }

        assertTrue(file.isFile)
    }

    @Test
    fun `open sets the pragmas the phone sets`() {
        JdbcSqlDriver.open(File(directory, "wardrobe.db")).use { driver ->
            // Without foreign keys a rating outlives its outfit: the cascade the
            // write paths rely on simply does not run.
            assertEquals(1L, (driver.query("PRAGMA foreign_keys;").single().values.single() as Number).toLong())
            assertEquals("wal", driver.query("PRAGMA journal_mode;").single().values.single())
        }
    }

    @Test
    fun `a write from another thread waits for an open transaction instead of joining it`() {
        JdbcSqlDriver.open(File(directory, "wardrobe.db")).use { driver ->
            driver.execute("CREATE TABLE t (x TEXT);")
            val insideTransaction = CountDownLatch(1)

            // One request's transaction, which fails after the other request has
            // tried to write. On a shared connection with no lock, that write
            // runs inside this transaction and is rolled back with it.
            val failing = thread {
                runCatching {
                    driver.transaction {
                        driver.execute("INSERT INTO t (x) VALUES ('rolled back');")
                        insideTransaction.countDown()
                        Thread.sleep(300)
                        error("this request failed")
                    }
                }
            }

            assertTrue(insideTransaction.await(5, TimeUnit.SECONDS))
            driver.execute("INSERT INTO t (x) VALUES ('kept');")
            failing.join()

            assertEquals(listOf("kept"), driver.query("SELECT x FROM t;").map { it["x"] })
        }
    }

    @Test
    fun `a nested transaction joins the outer one`() {
        JdbcSqlDriver.open(File(directory, "wardrobe.db")).use { driver ->
            driver.execute("CREATE TABLE t (x TEXT);")

            runCatching {
                driver.transaction {
                    driver.transaction { driver.execute("INSERT INTO t (x) VALUES ('inner');") }
                    error("the outer one fails")
                }
            }

            assertEquals(emptyList(), driver.query("SELECT x FROM t;"))
        }
    }
}
