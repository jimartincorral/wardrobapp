package com.wardrobapp.data

import com.wardrobapp.domain.Garment

/**
 * A garment as the database holds it, after normalization.
 *
 * Distinct from the domain's [Garment] on purpose. The algorithms need a
 * garment's category, colours, tags and size; they have no business knowing
 * where its photos live or when the row was written. Keeping those here means
 * the domain type stays the narrow thing the domain actually uses, and this
 * carries everything a screen or a backup needs.
 *
 * `createdAt` and `updatedAt` are nullable because the shipped schema really is
 * inconsistent about them: a fresh install gets them via CREATE TABLE as
 * NOT NULL, while an install upgraded through the ALTER path gets them nullable,
 * since SQLite cannot add a NOT NULL column without a default and none was
 * supplied. Both populations exist, so this has to tolerate both.
 */
data class GarmentRecord(
    val id: String,
    val imageUri: String,
    val imageUriNoBg: String?,
    val imageUris: List<String>,
    val imageUrisNoBg: List<String>,
    val category: String,
    val subcategory: String?,
    val subcategories: List<String>,
    val tags: List<String>,
    val brand: String?,
    val colorPrimary: String,
    val colorSecondary: String?,
    val colorPalette: List<String>,
    val size: String?,
    val purchaseDate: String?,
    val isAvailable: Boolean,
    val unavailableDate: String?,
    val createdAt: String?,
    val updatedAt: String?,
) {
    /** The narrow view the algorithms take. */
    fun toDomain(): Garment = Garment(
        id = id,
        category = category,
        subcategory = subcategory,
        subcategories = subcategories,
        tags = tags,
        brand = brand,
        colorPrimary = colorPrimary,
        colorSecondary = colorSecondary,
        colorPalette = colorPalette,
        size = size,
        isAvailable = isAvailable,
    )

    /** Photos to show, falling back to the single stored reference. */
    val displayImageUris: List<String>
        get() = imageUris.ifEmpty { listOfNotNull(imageUri).filter { it.isNotEmpty() } }

    /** The background-removed photo if there is one, else the plain photo. */
    val displayImage: String
        get() = imageUrisNoBg.firstOrNull { it.isNotEmpty() }
            ?: displayImageUris.firstOrNull()
            ?: imageUri
}
