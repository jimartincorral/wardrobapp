package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.SuggestedOutfit
import com.wardrobapp.domain.GarmentReading
import com.wardrobapp.domain.PhantomGarment
import com.wardrobapp.domain.ReadType
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShoppingCheckTest {

    private val navy = "#000080"
    private val black = "#000000"

    private fun garment(id: String, category: String, type: String, colour: String, available: Boolean = true): GarmentRecord =
        testGarment(id, category, type).copy(colorPrimary = colour, colorPalette = listOf(colour), isAvailable = available)

    @Test
    fun `the same kind in the same colour is told apart from the same kind in another`() {
        val wardrobe = listOf(
            garment("navy-blazer", "outerwear", "Blazer", navy),
            garment("black-blazer", "outerwear", "Blazer", black),
            garment("navy-coat", "outerwear", "Coat", navy),
            garment("old-blazer", "outerwear", "Blazer", navy, available = false),
        )

        val (same, others) = ownedLike(PhantomGarment("outerwear", "Blazer", navy), wardrobe)

        assertEquals(listOf("navy-blazer"), same.map { it.id })
        assertEquals(listOf("black-blazer"), others.map { it.id }, "a coat is not a blazer, and a retired blazer is not owned")
    }

    @Test
    fun `with no colour read nothing is claimed to be the same, and with no type the category is the kind`() {
        val wardrobe = listOf(
            garment("navy-blazer", "outerwear", "Blazer", navy),
            garment("navy-coat", "outerwear", "Coat", navy),
        )

        val (same, others) = ownedLike(PhantomGarment("outerwear", "Blazer", ""), wardrobe)
        assertTrue(same.isEmpty())
        assertEquals(listOf("navy-blazer"), others.map { it.id })

        val (anyKind, _) = ownedLike(PhantomGarment("outerwear", null, navy), wardrobe)
        assertEquals(listOf("navy-blazer", "navy-coat"), anyKind.map { it.id })
    }

    private class FakePhotos : PhotoWork<String> {
        var reading: GarmentReading? = GarmentReading(type = ReadType("outerwear", "Blazer"))
        var colours: List<String>? = listOf("#000080")
        val stored = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        override suspend fun store(photo: String) = "stored/$photo".also { stored += it }
        override suspend fun delete(photo: String) {
            deleted += photo
        }
        override suspend fun colors(photo: String) = colours
        override suspend fun cutOut(photo: String) = photo
        override suspend fun read(photo: String) = reading
    }

    private class FakeSource : ShoppingCheckSource {
        val asked = mutableListOf<PhantomGarment>()
        override suspend fun wardrobe() = listOf(
            testGarment("navy-blazer", "outerwear", "Blazer").copy(colorPrimary = "#000080"),
            testGarment("jeans", "bottoms", "Jeans"),
        )
        override suspend fun outfitsWith(wanted: PhantomGarment): List<SuggestedOutfit> {
            asked += wanted
            return List(5) { SuggestedOutfit(name = "o$it", score = 1.0, garments = listOf(testGarment("jeans", "bottoms", "Jeans"))) }
        }
    }

    @Test
    fun `a photo is read, checked against the wardrobe, and thrown away after`() = runTest {
        val photos = FakePhotos()
        val source = FakeSource()
        val model = ShoppingCheckScreenModel(this, photos, source)

        model.onPhotoPicked("shop.jpg")
        advanceUntilIdle()

        val state = model.state.value
        assertEquals("stored/shop.jpg", state.photo)
        assertEquals(PhantomGarment("outerwear", "Blazer", "#000080"), state.wanted)
        assertEquals(listOf("navy-blazer"), state.same.map { it.id })
        assertEquals(SHOPPING_CHECK_OUTFITS, state.outfits.size, "more outfits were kept than the screen shows")

        model.onPhotoPicked("second.jpg")
        advanceUntilIdle()
        assertEquals(listOf("stored/shop.jpg"), photos.deleted, "the first photo outlived the second check")

        model.onLeaving(this)
        advanceUntilIdle()
        assertEquals(listOf("stored/shop.jpg", "stored/second.jpg"), photos.deleted)
    }

    @Test
    fun `a photo nothing can make out says so and checks nothing`() = runTest {
        val photos = FakePhotos().apply { reading = null }
        val source = FakeSource()
        val model = ShoppingCheckScreenModel(this, photos, source)

        model.onPhotoPicked("shop.jpg")
        advanceUntilIdle()

        assertTrue(model.state.value.unread)
        assertNull(model.state.value.wanted)
        assertTrue(source.asked.isEmpty())
    }
}
