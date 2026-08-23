package com.wardrobapp.domain

/**
 * Telling the tags a user typed from the ones that are really structured data.
 *
 * A garment's tags column carries both: free text the user typed, and season
 * values that are a filter rather than a label. It also carries values from
 * filters that no longer exist -- weather was removed for largely restating
 * season, and occasion is now derived from a garment's type instead of tagged.
 *
 * Old rows still contain those, and a one-time migration is not enough on its
 * own: restoring an older backup puts them straight back, and they would then
 * surface as if someone had typed them.
 */
private val LEGACY_STRUCTURED_TAGS = setOf(
    // weather
    "hot", "warm", "cool", "cold", "rainy", "snowy", "windy",
    // occasion
    "casual", "work", "formal", "sport", "lounge", "party", "travel",
)

/** Tags as a screen should show them, split from the structured values. */
data class StructuredTags(
    val customTags: List<String>,
    val seasons: List<Season>,
)

fun splitStructuredTags(tags: List<String>): StructuredTags {
    val customTags = mutableListOf<String>()
    val seasons = mutableListOf<Season>()

    for (rawTag in tags) {
        val tag = rawTag.trim().lowercase()
        if (tag.isEmpty()) continue

        val season = Season.fromTag(tag)
        if (season != null) {
            seasons.add(season)
            continue
        }
        if (tag in LEGACY_STRUCTURED_TAGS) continue

        customTags.add(tag)
    }

    return StructuredTags(customTags, seasons)
}
