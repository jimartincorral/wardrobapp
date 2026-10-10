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
    /** What was set on the garment; see GarmentAttributes for why these are tags. */
    val attributes: GarmentAttributes = GarmentAttributes.NONE,
)

/**
 * Fold what the user typed and the seasons they picked into the tags column.
 *
 * The inverse of [splitStructuredTags]: the column carries both, so the two have
 * to agree about normalisation or a garment saved by one app reads differently in
 * the other. Everything is trimmed and lowercased, blanks are dropped, and a
 * value appearing twice -- as a typed tag and as a season, or in two cases --
 * appears once.
 */
fun mergeStructuredTags(
    customTags: List<String>,
    seasons: List<Season>,
    attributes: GarmentAttributes = GarmentAttributes.NONE,
): List<String> {
    val merged = mutableListOf<String>()
    val seen = mutableSetOf<String>()

    // A typed tag that happens to carry an attribute prefix is not kept as
    // typed: the attributes passed in are the whole truth about attributes,
    // or clearing one in the form would leave its old tag behind.
    val typed = customTags.map { it.trim().lowercase() }.filterNot { GarmentAttributes.isAttributeTag(it) }

    for (tag in typed + seasons.map { it.tag } + attributes.toTags()) {
        if (tag.isEmpty() || !seen.add(tag)) continue
        merged.add(tag)
    }

    return merged
}

fun splitStructuredTags(tags: List<String>): StructuredTags {
    val customTags = mutableListOf<String>()
    val seasons = mutableListOf<Season>()

    for (rawTag in tags) {
        val tag = rawTag.trim().lowercase()
        if (tag.isEmpty()) continue
        if (GarmentAttributes.isAttributeTag(tag)) continue

        val season = Season.fromTag(tag)
        if (season != null) {
            seasons.add(season)
            continue
        }
        if (tag in LEGACY_STRUCTURED_TAGS) continue

        customTags.add(tag)
    }

    return StructuredTags(customTags, seasons, GarmentAttributes.fromTags(tags))
}
