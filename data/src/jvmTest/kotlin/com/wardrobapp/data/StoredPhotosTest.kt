package com.wardrobapp.data

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StoredPhotosTest {

    private val root: File = File.createTempFile("photos-test", "").let { placeholder ->
        placeholder.delete()
        placeholder.mkdirs()
        placeholder
    }

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun `a directory that does not exist yet uses nothing`() {
        assertEquals(0L, storedImageBytes(File(root, "never-created")))
    }

    @Test
    fun `an empty directory uses nothing`() {
        val dir = File(root, "empty").apply { mkdirs() }
        assertEquals(0L, storedImageBytes(dir))
    }

    @Test
    fun `it sums the photos`() {
        val dir = File(root, "photos").apply { mkdirs() }
        File(dir, "a.jpg").writeText("12345")
        File(dir, "b.jpg").writeText("123")
        assertEquals(8L, storedImageBytes(dir))
    }

    @Test
    fun `it ignores subdirectories`() {
        val dir = File(root, "photos").apply { mkdirs() }
        File(dir, "a.jpg").writeText("12345")
        File(dir, "thumbnails").apply { mkdirs() }
        File(dir, "thumbnails/b.jpg").writeText("ignored")
        assertEquals(5L, storedImageBytes(dir))
    }

    @Test
    fun `a file handed in where a directory was expected is nothing rather than a throw`() {
        val file = File(root, "not-a-directory").apply { writeText("x") }
        assertEquals(0L, storedImageBytes(file))
    }
}
