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

/** What the user asked for, if anything. */
data class SuggestionPreferences(
    val seasons: List<Season> = emptyList(),
    val occasion: Occasion? = null,
)
