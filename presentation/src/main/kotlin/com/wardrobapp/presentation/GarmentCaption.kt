package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord

/**
 * What a garment's cell says under its photo.
 *
 * The wardrobe's grid gives each garment one line, and until now that line was the
 * brand -- falling back to the type, then the category -- because a wall of photos
 * shows everything about a garment except who made it. That is one reasonable
 * reading, and it was mine rather than anybody's choice, so it is a choice now.
 *
 * Pure and here rather than in the composable for the reason [WardrobeView] gives:
 * what a stored value means, what an unrecognised one means, and what a cell shows
 * when the garment has not got the field asked for are rules, and a rule that lives
 * in a layout cannot be asked what it would say.
 */
enum class GarmentCaption { BRAND, TYPE, CATEGORY }

/**
 * The value to store, or null to store nothing.
 *
 * Null for the brand, so the caption this app has always shown is recorded as the
 * absence of a choice -- the same shape as [WardrobeLayout.storedValue] and
 * [ThemeChoice.storedValue], and for the same reason: a fresh install and a
 * deliberate return to it are one state.
 */
val GarmentCaption.storedValue: String?
    get() = when (this) {
        GarmentCaption.BRAND -> null
        GarmentCaption.TYPE -> "type"
        GarmentCaption.CATEGORY -> "category"
    }

/** The brand for anything unrecognised, which is what the cells shipped as. */
fun garmentCaptionFor(stored: String?): GarmentCaption =
    when (stored?.trim()?.lowercase()) {
        "type" -> GarmentCaption.TYPE
        "category" -> GarmentCaption.CATEGORY
        else -> GarmentCaption.BRAND
    }

/**
 * What to try, in order, for a given choice.
 *
 * Every chain ends at [GarmentCaption.CATEGORY] because category is the one column
 * every row has: a chain that could run out would leave a cell with a blank line
 * under the photo, which looks like a bug rather than like an empty field.
 *
 * The type does not fall back to the brand, deliberately. Somebody who asked for
 * the type asked for what a garment *is*; answering with who made it, on the cells
 * that happen to have no subcategory, would mean a grid where the line means
 * different kinds of thing from one cell to the next. The category is the same kind
 * of answer, one step coarser.
 */
private fun captionChain(choice: GarmentCaption): List<GarmentCaption> = when (choice) {
    GarmentCaption.BRAND ->
        listOf(GarmentCaption.BRAND, GarmentCaption.TYPE, GarmentCaption.CATEGORY)
    GarmentCaption.TYPE -> listOf(GarmentCaption.TYPE, GarmentCaption.CATEGORY)
    GarmentCaption.CATEGORY -> listOf(GarmentCaption.CATEGORY)
}

/**
 * Which field this garment's cell can actually show.
 *
 * A field rather than a string, because turning a stored "tshirt" into "Camiseta"
 * is the vocabulary's job and the vocabulary is in :app -- the same split
 * [backupsToRemove] keeps between deciding and doing.
 */
fun GarmentRecord.captionField(choice: GarmentCaption): GarmentCaption =
    captionChain(choice).firstOrNull { has(it) } ?: GarmentCaption.CATEGORY

/** Blank counts as absent: a brand stored as a space is not a brand. */
private fun GarmentRecord.has(field: GarmentCaption): Boolean = when (field) {
    GarmentCaption.BRAND -> !brand.isNullOrBlank()
    GarmentCaption.TYPE -> !subcategory.isNullOrBlank()
    GarmentCaption.CATEGORY -> category.isNotBlank()
}
