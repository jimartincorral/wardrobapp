package com.wardrobapp.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which files a garment has stopped referencing.
 *
 * The decision that gates deleting a photo, so both directions are wrong in a way
 * nobody reports: name a file still in use and it disappears from under the
 * garment; miss one and it sits on the phone with nothing pointing at it.
 */
class OrphanedImageRefsTest {

    private val directory = "file:///data/user/0/com.anonymous.wardrobapp/files/garment-images/"
    private val olderInstall = "file:///data/user/0/com.anonymous.wardrobapp/files/old/garment-images/"

    @Test
    fun `names a photo that was dropped`() {
        assertEquals(
            listOf("b.jpg"),
            orphanedImageRefs(listOf("a.jpg", "b.jpg"), listOf("a.jpg")),
        )
    }

    @Test
    fun `names nothing when everything is still in use`() {
        assertEquals(
            emptyList(),
            orphanedImageRefs(listOf("a.jpg", "b.jpg"), listOf("b.jpg", "a.jpg")),
        )
    }

    @Test
    fun `matches on the stored filename, whatever form the reference is in`() {
        // The same photo is named differently depending on where it came from: a
        // resolved URI from a read, a bare filename from the database, an absolute
        // path from an older build. Comparing the strings as given would report a
        // file still in use, and it would be deleted.
        val previous = listOf("${directory}a.jpg", "${olderInstall}b.jpg", "c.jpg")

        assertEquals(
            emptyList(),
            orphanedImageRefs(previous, listOf("a.jpg", "${directory}b.jpg", "${directory}c.jpg")),
        )
    }

    @Test
    fun `names a cut-out that was undone`() {
        // The garment held a cut-out in both columns; the edit put the original
        // back, so the cut-out is now unreferenced.
        assertEquals(
            listOf("a-cut.png"),
            orphanedImageRefs(listOf("a-cut.png", "a-cut.png"), listOf("a.jpg", "")),
        )
    }

    @Test
    fun `names each file once, however many times it was referenced`() {
        // A collapsed garment holds the same file in both columns, so a naive pass
        // returns it twice and the second delete has nothing to delete.
        assertEquals(
            listOf("gone.png"),
            orphanedImageRefs(listOf("gone.png", "gone.png", "${directory}gone.png"), emptyList()),
        )
    }

    @Test
    fun `ignores blanks on either side`() {
        // The cut-out column holds "" where a photo has none, and "" is not a file.
        assertEquals(listOf("a.jpg"), orphanedImageRefs(listOf("", "a.jpg", ""), listOf("", "")))
        assertEquals(emptyList(), orphanedImageRefs(emptyList(), listOf("a.jpg")))
    }

    @Test
    fun `names everything when a garment is emptied`() {
        assertEquals(
            listOf("a.jpg", "b.jpg"),
            orphanedImageRefs(listOf("a.jpg", "b.jpg"), emptyList()),
        )
    }

    @Test
    fun `tells a stored photo from a temporary one`() {
        // What keeps a form from deleting a saved garment's photo: a cut-out this
        // form wrote is disposable, one already in the database is not.
        assertEquals(true, isStoredGarmentImage("${directory}abc_nobg.png", directory))
        assertEquals(false, isStoredGarmentImage("${directory}nested/x.jpg", directory))
        assertEquals(false, isStoredGarmentImage("file:///tmp/garment-images/x.jpg", directory))
        // A bare filename is what the *database* stores, so an empty directory must
        // not make one look like a file on disk.
        assertEquals(false, isStoredGarmentImage("abc.jpg", ""))
        assertEquals(false, isStoredGarmentImage("", directory))
    }
}
