package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import com.wardrobapp.domain.garmentLabelFor
import com.wardrobapp.domain.outfitNameFrom

/**
 * Putting an outfit together, and changing one.
 *
 * Building and editing are the same job with a different starting point: a set of
 * garments, a name, and what the outfit is for. So they are one state and one
 * screen rather than two that drift -- the same reason the garment form does both
 * adding and editing.
 *
 * The garments are held as ids in the order they were chosen, because that order
 * is what the outfit lists and what every screen then draws. Nothing here reads a
 * database or knows what a photo is.
 */
data class OutfitEditState(
    /** What somebody typed, which may be nothing. */
    val name: String = "",
    /** The garments, in the order they were picked. */
    val garmentIds: List<String> = emptyList(),
    val occasion: Occasion? = null,
    val season: Season? = null,
) {
    /**
     * Whether this is an outfit yet.
     *
     * One garment is enough: the suggestion engine builds single-garment outfits
     * out of a dress, so refusing one here would make the builder stricter than
     * the thing it is meant to be an alternative to.
     */
    val canSave: Boolean get() = garmentIds.isNotEmpty()

    /** Whether a garment is in the outfit, for a picker to show. */
    fun holds(garmentId: String): Boolean = garmentId in garmentIds

    /**
     * Add a garment, or take it out again.
     *
     * Added at the end rather than in wardrobe order: the order garments were
     * picked in is the only order this screen knows about, and re-sorting under
     * somebody's finger is how a list becomes hard to use.
     */
    fun withGarmentToggled(garmentId: String): OutfitEditState = copy(
        garmentIds = if (garmentId in garmentIds) {
            garmentIds - garmentId
        } else {
            garmentIds + garmentId
        },
    )

    fun withName(next: String): OutfitEditState = copy(name = next)

    /**
     * Choose what the outfit is for, or clear it.
     *
     * Tapping the one already chosen clears it, because an outfit that is not for
     * anything in particular has to be expressible -- and a chip row with no way
     * back is a choice you cannot undo.
     */
    fun withOccasion(next: Occasion): OutfitEditState =
        copy(occasion = if (occasion == next) null else next)

    fun withSeason(next: Season): OutfitEditState =
        copy(season = if (season == next) null else next)

    /**
     * What this outfit should be stored as.
     *
     * A typed name wins. An untitled outfit is named from its garments, the way a
     * suggestion is -- so an outfit built by hand and left untitled sits in the
     * list looking like every other outfit rather than like a blank row.
     *
     * The garments are looked up rather than passed as labels so the order is the
     * outfit's own: the name should read in the order the clothes were picked.
     */
    fun nameFor(garments: List<GarmentRecord>): String {
        val typed = name.trim()
        if (typed.isNotEmpty()) return typed

        val byId = garments.associateBy { it.id }

        return outfitNameFrom(
            garmentIds.mapNotNull { id ->
                byId[id]?.let { garmentLabelFor(it.category, it.subcategory) }
            }
        )
    }

    /** The chosen garments, in the order they were chosen. */
    fun chosen(garments: List<GarmentRecord>): List<GarmentRecord> {
        val byId = garments.associateBy { it.id }
        return garmentIds.mapNotNull { byId[it] }
    }
}

/**
 * The state an existing outfit starts an edit in.
 *
 * Its stored occasion and season are strings, and one this app does not recognise
 * -- from an older build, or a restored backup -- becomes null rather than being
 * kept as something the chips cannot show: a value no row of buttons can display
 * is a value nobody can change.
 */
fun outfitEditStateOf(
    name: String,
    garmentIds: List<String>,
    occasion: String?,
    season: String?,
): OutfitEditState = OutfitEditState(
    name = name,
    garmentIds = garmentIds,
    occasion = occasion?.let { Occasion.fromId(it) },
    season = season?.let { stored -> Season.entries.find { it.tag == stored.lowercase().trim() } },
)
