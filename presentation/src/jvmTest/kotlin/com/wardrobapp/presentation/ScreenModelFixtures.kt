package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.OutfitRecord

/** A garment with nothing set beyond what a screen model test needs. */
internal fun testGarment(id: String, category: String = "tops", subcategory: String? = null) = GarmentRecord(
    id = id,
    imageUri = "$id.jpg",
    imageUriNoBg = null,
    imageUris = listOf("$id.jpg"),
    imageUrisNoBg = emptyList(),
    category = category,
    subcategory = subcategory,
    subcategories = listOfNotNull(subcategory),
    tags = emptyList(),
    brand = null,
    colorPrimary = "#000000",
    colorSecondary = null,
    colorPalette = listOf("#000000"),
    size = null,
    purchaseDate = null,
    isAvailable = true,
    unavailableDate = null,
    createdAt = null,
    updatedAt = null,
)

/** An outfit of [garmentIds], saved rather than suggested. */
internal fun testOutfit(id: String, vararg garmentIds: String) = OutfitRecord(
    id = id,
    name = "Outfit $id",
    garmentIds = garmentIds.toList(),
    occasion = null,
    season = null,
    createdAt = null,
    isSuggested = false,
    isPinned = false,
)
