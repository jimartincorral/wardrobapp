package com.wardrobapp.presentation

import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season

/**
 * The season and occasion chips on the outfits screen.
 *
 * Small, but worth having in one place: the React Native screen decided whether
 * a chip was active twice per chip -- once for its background and once for its
 * text -- and the two expressions had to agree. They are the sort of thing that
 * stays in step right up until one of them is edited.
 *
 * The two rows behave differently on purpose. Seasons are a set, because a
 * garment for spring is often a garment for fall; occasion is one choice,
 * because an outfit is for one thing at a time.
 */
data class OutfitFilters(
    val seasons: List<Season> = emptyList(),
    val occasion: Occasion? = null,
) {
    /** True when nothing is filtered, which is what the empty-state copy turns on. */
    val isUnfiltered: Boolean get() = seasons.isEmpty() && occasion == null
}

/**
 * One chip: what it stands for, and whether it is on.
 *
 * A null value is the "any" chip, which stands for the absence of a choice
 * rather than for a value. It is derived rather than stored, so there is only
 * one empty state to reach and the row always reads one way.
 */
data class FilterChip<T>(val value: T?, val active: Boolean)

fun OutfitFilters.seasonChips(): List<FilterChip<Season>> =
    listOf<FilterChip<Season>>(FilterChip(null, seasons.isEmpty())) +
        Season.entries.map { FilterChip(it, it in seasons) }

fun OutfitFilters.occasionChips(): List<FilterChip<Occasion>> =
    listOf<FilterChip<Occasion>>(FilterChip(null, occasion == null)) +
        Occasion.entries.map { FilterChip(it, it == occasion) }

/**
 * Tapping a season chip.
 *
 * "Any" clears the set rather than being a value in it. Tapping a season that is
 * already on takes it off, so the row can be emptied without reaching for "any"
 * -- and emptying it that way lands in the same state.
 */
fun OutfitFilters.withSeasonToggled(season: Season?): OutfitFilters {
    if (season == null) return copy(seasons = emptyList())

    val toggled = if (season in seasons) seasons - season else seasons + season

    // Kept in the app's own order rather than the order they were tapped, so the
    // same choice always reads the same -- and so it matches what the chips
    // show. The engine is indifferent: it tests membership.
    return copy(seasons = Season.entries.filter { it in toggled })
}

/**
 * Tapping an occasion chip.
 *
 * Tapping the active one clears it, which is the same as tapping "any" -- so
 * there is no state the user can reach and not get back out of.
 */
fun OutfitFilters.withOccasionSelected(occasion: Occasion?): OutfitFilters =
    if (occasion == null || occasion == this.occasion) copy(occasion = null) else copy(occasion = occasion)
