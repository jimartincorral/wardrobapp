package com.wardrobapp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which files a sweep may delete.
 *
 * The riskiest function in this module: every mistake it can make is silent. Name
 * a file that a garment is still showing and the photo disappears from a wardrobe;
 * miss one and the phone keeps carrying it. So the cases here are mostly about the
 * three ways one photo can be spelled, and about the blanks a garment's cut-out
 * column is full of.
 */
class UnreferencedPhotosTest {

    @Test
    fun `a file nothing points at is named, and one that is pointed at is not`() {
        val swept = unreferencedPhotos(
            present = listOf("kept.jpg", "orphan.jpg"),
            referenced = listOf("kept.jpg"),
        )

        assertEquals(listOf("orphan.jpg"), swept)
    }

    @Test
    fun `a reference spelled any of the three ways still protects its file`() {
        // The same photo, as the database holds it, as a read resolves it, and as
        // an older build wrote it. Any of them means "in use".
        val ways = listOf(
            "photo.jpg",
            "file:///data/user/0/com.wardrobapp/files/images/photo.jpg",
            "/data/user/0/com.wardrobapp/files/images/photo.jpg",
        )

        for (reference in ways) {
            assertTrue(
                unreferencedPhotos(listOf("photo.jpg"), listOf(reference)).isEmpty(),
                "a reference written as \"$reference\" did not protect its file",
            )
        }
    }

    @Test
    fun `a file named the long way is matched against a short reference too`() {
        // The pass reads the directory, so what it holds are bare names -- but the
        // comparison must not depend on that.
        assertTrue(
            unreferencedPhotos(
                present = listOf("file:///files/images/photo.jpg"),
                referenced = listOf("photo.jpg"),
            ).isEmpty()
        )
    }

    @Test
    fun `blanks in a garment's cut-out column do not protect anything, or condemn it`() {
        // A garment with photos and no cut-outs carries a blank per slot. A blank
        // treated as a reference would protect nothing; a blank compared as a name
        // would match everything.
        val swept = unreferencedPhotos(
            present = listOf("a.jpg", ""),
            referenced = listOf("b.jpg", "", ""),
        )

        assertEquals(listOf("a.jpg"), swept)
    }

    @Test
    fun `an empty wardrobe condemns every photo, and an empty directory nothing`() {
        // Both ends are real: a wardrobe whose garments were all deleted leaves
        // files nothing points at, and a fresh install has no files at all.
        assertEquals(
            listOf("a.jpg", "b.jpg"),
            unreferencedPhotos(listOf("a.jpg", "b.jpg"), emptyList()),
        )
        assertEquals(emptyList(), unreferencedPhotos(emptyList(), listOf("a.jpg")))
    }

    @Test
    fun `case is respected, because a filesystem respects it`() {
        // Unlike a colour hex, these are filenames on Android's filesystem, where
        // "Photo.jpg" and "photo.jpg" are two files. Treating them as one would
        // delete a photo that is in use.
        assertEquals(
            listOf("Photo.jpg"),
            unreferencedPhotos(listOf("Photo.jpg"), listOf("photo.jpg")),
        )
    }
}
