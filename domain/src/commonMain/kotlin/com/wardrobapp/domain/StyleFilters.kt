package com.wardrobapp.domain

/**
 * Seasons a garment can be tagged with, and that a suggestion can be filtered
 * by. `tag` is the lowercase string stored in a garment's tags.
 */
enum class Season(val tag: String) {
    SPRING("spring"),
    SUMMER("summer"),
    FALL("fall"),
    WINTER("winter"),
    ALL_SEASON("all-season");

    /** The season that argues against this one, or null for ALL_SEASON. */
    val opposite: Season?
        get() = when (this) {
            SPRING -> FALL
            SUMMER -> WINTER
            FALL -> SPRING
            WINTER -> SUMMER
            ALL_SEASON -> null
        }

    companion object {
        fun fromTag(tag: String): Season? = entries.find { it.tag == tag.lowercase().trim() }
    }
}

/**
 * Occasions a garment's *type* can imply.
 *
 * Occasion is derived from the garment's type rather than tagged by hand, so the
 * list is limited to what a type can actually tell you -- "party" and "travel"
 * were dropped upstream because nothing about a garment implies them, which made
 * filtering by them return nothing at all.
 *
 * Declaration order is the order results are reported in.
 */
enum class Occasion(val id: String) {
    CASUAL("casual"),
    WORK("work"),
    FORMAL("formal"),
    SPORT("sport"),
    LOUNGE("lounge");

    companion object {
        fun fromId(id: String): Occasion? = entries.find { it.id == id.lowercase().trim() }
    }
}

/**
 * The season a date falls in.
 *
 * The month arrives as an argument rather than being read from a clock, which is
 * the same reason the suggestion engine takes its randomness that way: a
 * suggestion run has to be reproducible. Zero-based to match what both
 * `Date.getMonth()` and `Calendar.MONTH` hand over, so neither caller has to
 * remember to shift it.
 *
 * Never returns [Season.ALL_SEASON]: that is a property a garment can have, not
 * a time of year it can be.
 */
fun seasonOfMonth(monthZeroBased: Int): Season = when (monthZeroBased) {
    in 2..4 -> Season.SPRING
    in 5..7 -> Season.SUMMER
    in 8..10 -> Season.FALL
    else -> Season.WINTER
}

/** What the user asked for, if anything. */
data class SuggestionPreferences(
    val seasons: List<Season> = emptyList(),
    val occasion: Occasion? = null,
)
