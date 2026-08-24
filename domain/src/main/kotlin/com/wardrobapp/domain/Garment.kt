package com.wardrobapp.domain

/**
 * A garment, as the algorithms see it.
 *
 * Camel-cased and non-nullable where the algorithms rely on a value being
 * present, which is a deliberate departure from the SQLite column names and
 * their permissive nulls. Mapping the two is the data layer's job; by the time a
 * garment reaches here the shape is settled.
 */
data class Garment(
    val id: String,
    val category: String,
    val subcategory: String? = null,
    val subcategories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val brand: String? = null,
    val colorPrimary: String,
    val colorSecondary: String? = null,
    val colorPalette: List<String> = emptyList(),
    val size: String? = null,
    val isAvailable: Boolean = true,
) {
    /**
     * The palette, falling back to the primary/secondary pair when no palette
     * was recorded. Never empty for a garment with a primary colour.
     */
    val palette: List<String>
        get() = colorPalette.ifEmpty { listOfNotNull(colorPrimary, colorSecondary).filter { it.isNotBlank() } }

    /** The colour that represents this garment in a harmony or duplicate check. */
    val primaryColor: String
        get() = palette.firstOrNull() ?: colorPrimary

    /** Subcategories, falling back to the single stored one. */
    val effectiveSubcategories: List<String>
        get() = subcategories.ifEmpty { listOfNotNull(subcategory).filter { it.isNotBlank() } }
}
