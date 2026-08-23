package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import com.wardrobapp.domain.occasions
import com.wardrobapp.domain.splitStructuredTags

/**
 * What a garment's detail screen shows.
 *
 * A record and which photo is selected in, everything the screen renders out.
 * Dates come out as the strings the database holds and colours as their keys:
 * formatting a date and naming a colour are the platform's business, and a port
 * that reproduced date-fns's `MMM d, yyyy` in English would be worse on Android
 * than using the device's own locale. What is decided here is *which* things are
 * shown and what they refer to.
 */

/** One entry of the palette: the colour itself, and its name if it has one. */
data class PaletteEntry(
    val hex: String,
    /**
     * The key in the app's colour list, or null for a colour that was not
     * picked from it. Matched case-insensitively, because the wardrobe holds the
     * same hex in both cases.
     */
    val colorKey: String?,
)

/** One photo in the thumbnail strip. */
data class GalleryEntry(
    /** What the thumbnail shows: the cut-out for this slot if there is one. */
    val uri: String,
    val selected: Boolean,
    /** True when this slot has a cut-out, which is what "undo" would discard. */
    val hasCutout: Boolean,
)

/**
 * What the background-removal button offers for the selected photo.
 *
 * `UNDO` only while a separate original still exists to go back to. Removing a
 * background replaces the photo it came from, so for anything imported since
 * that change there is nothing to revert to, and offering it would be a button
 * that destroys the only copy.
 */
enum class BackgroundAction { REMOVE, UNDO }

data class GarmentDetailView(
    /** The large photo, or null for a garment with no usable photo at all. */
    val displayedImage: String?,
    val gallery: List<GalleryEntry>,
    /** The strip is worth drawing only when there is a choice to make. */
    val showsGallery: Boolean,
    /** Which gallery entry is selected, after clamping the caller's index. */
    val selectedIndex: Int,
    val category: String,
    val subcategories: List<String>,
    val brand: String?,
    val size: String?,
    val seasons: List<Season>,
    val occasions: List<Occasion>,
    val palette: List<PaletteEntry>,
    /** Tags the user typed, with the structured ones taken out. */
    val tags: List<String>,
    val backgroundAction: BackgroundAction?,
    val isAvailable: Boolean,
    /** The strings the database holds, for the caller to format. */
    val unavailableDate: String?,
    val purchaseDate: String?,
)

/**
 * The colours the picker offers, by key.
 *
 * A transcription of src/constants/colors.ts. `#RAINBOW` is not a colour: it is
 * the sentinel for "several", and the picker stores it like any other value.
 */
val GARMENT_COLORS: List<Pair<String, String>> = listOf(
    "black" to "#000000",
    "white" to "#FFFFFF",
    "gray" to "#808080",
    "navy" to "#000080",
    "blue" to "#0066CC",
    "lightBlue" to "#87CEEB",
    "red" to "#CC0000",
    "burgundy" to "#800020",
    "pink" to "#FF69B4",
    "green" to "#228B22",
    "olive" to "#808000",
    "khaki" to "#C3B091",
    "brown" to "#8B4513",
    "tan" to "#D2B48C",
    "beige" to "#F5F5DC",
    "cream" to "#FFFDD0",
    "yellow" to "#FFD700",
    "orange" to "#FF8C00",
    "purple" to "#800080",
    "lavender" to "#E6E6FA",
    "coral" to "#FF7F50",
    "teal" to "#008080",
    "gold" to "#DAA520",
    "silver" to "#C0C0C0",
    "multi" to "#RAINBOW",
)

private val COLOR_KEYS_BY_HEX: Map<String, String> =
    GARMENT_COLORS.associate { (key, hex) -> hex.uppercase() to key }

/** Blank is not a value: a field of spaces is not worth drawing a row for. */
private fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/**
 * What the background-removal control should offer for one photo.
 *
 * Public because two screens ask it: a garment's detail, and the add/edit form.
 * The form used to work it out from "is there an original", which is true for a
 * photo that has no cut-out at all -- so it offered undo where there was nothing
 * to undo.
 */
fun backgroundActionFor(original: String?, cutout: String?): BackgroundAction? = when {
    cutout.isNullOrEmpty() -> BackgroundAction.REMOVE
    !original.isNullOrEmpty() && original != cutout -> BackgroundAction.UNDO
    else -> null
}

/**
 * The photo slots after a background action, and the file left unreferenced.
 *
 * Returned together because they have to be applied together: writing the rows
 * without deleting the file leaks it, and deleting the file without writing the
 * rows leaves a garment pointing at nothing.
 */
data class BackgroundEdit(
    val images: List<String>,
    val cutouts: List<String>,
    /** The file nothing references any more, if any. Never one still in use. */
    val discardable: String?,
)

/**
 * Replace a photo with its cut-out.
 *
 * The cut-out becomes the *only* image for that slot, in both columns -- which is
 * what makes the original discardable, and why no undo is offered afterwards:
 * there is nothing left to go back to. That is deliberate rather than a
 * limitation, and it is what both the React Native screen and this app's own form
 * already do, so a garment ends up in the same shape however it got there.
 *
 * The cut-out column is padded to match, because a garment whose photos predate
 * the column has one blank entry standing for all of them, and writing into slot
 * two of a one-element list would otherwise put the cut-out on the wrong photo.
 */
fun withBackgroundRemovedAt(
    images: List<String>,
    cutouts: List<String>,
    index: Int,
    cutout: String,
): BackgroundEdit? {
    val original = images.getOrNull(index) ?: return null
    if (cutout.isEmpty()) return null

    val padded = MutableList(images.size) { cutouts.getOrNull(it) ?: "" }
    val updated = images.toMutableList()
    updated[index] = cutout
    padded[index] = cutout

    return BackgroundEdit(
        images = updated,
        cutouts = padded,
        // Only when it is genuinely a different file: removing a background twice
        // would otherwise delete the photo it just produced.
        discardable = original.takeIf { it.isNotEmpty() && it != cutout },
    )
}

/**
 * Put the original photo back, dropping the cut-out.
 *
 * Only the cut-out column changes: the original is still what the image column
 * holds, which is precisely the case where an undo has somewhere to go. A slot
 * whose cut-out already replaced its original is refused rather than silently
 * clearing a reference the photo itself depends on.
 */
fun withBackgroundRestoredAt(
    images: List<String>,
    cutouts: List<String>,
    index: Int,
): BackgroundEdit? {
    val cutout = cutouts.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: return null
    val original = images.getOrNull(index) ?: return null
    if (original.isEmpty() || original == cutout) return null

    val padded = MutableList(images.size) { cutouts.getOrNull(it) ?: "" }
    padded[index] = ""

    return BackgroundEdit(images = images, cutouts = padded, discardable = cutout)
}

fun garmentDetail(garment: GarmentRecord, selectedIndex: Int = 0): GarmentDetailView {
    val images = garment.displayImageUris
    val cutouts = garment.displayNoBgImageUris

    // An index from outside -- a remembered selection, a garment whose photos
    // were edited since -- is clamped rather than trusted. Reading past the end
    // fell through to the first photo while the strip showed nothing selected,
    // so the screen disagreed with itself.
    val selected = if (selectedIndex in images.indices) selectedIndex else 0

    val gallery = images.mapIndexed { index, uri ->
        val cutout = cutouts.getOrNull(index)
        GalleryEntry(
            uri = cutout?.takeIf { it.isNotEmpty() } ?: uri,
            selected = index == selected,
            hasCutout = !cutout.isNullOrEmpty(),
        )
    }

    val (customTags, seasons) = splitStructuredTags(garment.tags)

    return GarmentDetailView(
        // No first-photo fallback: the clamp above already guarantees the
        // selected slot exists whenever there is any photo at all, so a
        // fallback there would be a branch no test could ever reach.
        displayedImage = cutouts.getOrNull(selected)?.takeIf { it.isNotEmpty() }
            ?: images.getOrNull(selected),
        gallery = gallery,
        showsGallery = gallery.size > 1,
        selectedIndex = selected,
        category = garment.category,
        // Not filtered for blanks: normalizeGarmentRow already trims them and
        // drops the empties, so a second guarantee here could never fire.
        subcategories = garment.subcategories,
        brand = garment.brand.orNullIfBlank(),
        size = garment.size.orNullIfBlank(),
        // In the app's own season order rather than the order they were typed,
        // so two garments tagged the same read the same. Deduplicates too.
        seasons = Season.entries.filter { it in seasons },
        occasions = garment.toDomain().occasions(),
        palette = garment.palette.map { hex ->
            PaletteEntry(hex = hex, colorKey = COLOR_KEYS_BY_HEX[hex.trim().uppercase()])
        },
        tags = customTags,
        backgroundAction = backgroundActionFor(images.getOrNull(selected), cutouts.getOrNull(selected)),
        isAvailable = garment.isAvailable,
        unavailableDate = if (garment.isAvailable) null else garment.unavailableDate.orNullIfBlank(),
        purchaseDate = garment.purchaseDate.orNullIfBlank(),
    )
}
