package com.wardrobapp.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Turning a photo reference into what the database keeps, and back.
 *
 * The absolute path to the photo directory is not stable across installs, so the
 * database stores a filename and the directory is re-attached on read. Rows
 * written by older builds still hold absolute paths, which is why reading has to
 * re-base as well as resolve -- otherwise a wardrobe restored onto a new install
 * shows nothing until a migration has run.
 *
 * Anything already portable is passed through untouched in both directions:
 * reducing a `content://` document to its last path segment would destroy it.
 */
class ImagePathsTest {

    private val directory = "file:///data/user/0/com.anonymous.wardrobapp/files/garment-images/"
    private val older = "file:///data/user/0/com.anonymous.wardrobapp/files/old/garment-images/"

    @Test
    fun `a local path is stored as its filename`() {
        assertEquals("photo.jpg", toStoredImageRef("${directory}photo.jpg"))
        assertEquals("photo.jpg", toStoredImageRef("${older}photo.jpg"))
        assertEquals("photo.jpg", toStoredImageRef("photo.jpg"))
        assertEquals("", toStoredImageRef(""))
    }

    @Test
    fun `a reference that is already portable is left alone`() {
        // Every one of these would be destroyed by taking its last path segment.
        for (ref in listOf(
            "content://com.android.providers.media.documents/document/image%3A1000",
            "https://cdn.example.com/products/shirt.jpg",
            "http://cdn.example.com/products/shirt.jpg",
            "data:image/png;base64,iVBORw0KGgo=",
            "blob:https://example.com/8e3f",
        )) {
            assertEquals(ref, toStoredImageRef(ref), "stored")
            assertEquals(ref, resolveImageRef(ref, directory), "resolved")
        }

        // And the check is on the scheme, not its spelling.
        assertEquals("HTTPS://cdn.example.com/a.jpg", toStoredImageRef("HTTPS://cdn.example.com/a.jpg"))
    }

    @Test
    fun `reading re-attaches the current directory`() {
        assertEquals("${directory}photo.jpg", resolveImageRef("photo.jpg", directory))
        // A path from an older install is re-based rather than trusted: the
        // directory it names may not exist any more.
        assertEquals("${directory}photo.jpg", resolveImageRef("${older}photo.jpg", directory))
        // Already in the right place: the same answer, not a doubled prefix.
        assertEquals("${directory}photo.jpg", resolveImageRef("${directory}photo.jpg", directory))
    }

    @Test
    fun `with no directory yet, a reference is returned unchanged`() {
        // Before the filesystem is available there is nothing to resolve against,
        // and resolving against nothing would produce a path that looks real.
        assertEquals("photo.jpg", resolveImageRef("photo.jpg", ""))
        assertEquals("", resolveImageRef("", directory))
    }

    @Test
    fun `storing and resolving are inverses of each other`() {
        for (ref in listOf("photo.jpg", "${older}photo.jpg", "${directory}photo.jpg")) {
            assertEquals(
                "${directory}photo.jpg",
                resolveImageRef(toStoredImageRef(ref), directory),
                "round trip of $ref",
            )
        }
    }
}
