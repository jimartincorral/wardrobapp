package com.wardrobapp.domain

/**
 * How the domain's own enums map onto the strings the TypeScript emits, so the
 * two can be compared. Kept next to the domain tests rather than in the shared
 * parity module: the generic fixture loading is reusable, these mappings are not.
 */
/** The i18n keys the TypeScript emits, so reasons can be compared across sides. */
internal fun DuplicateReason.tsKey(): String = when (this) {
    DuplicateReason.SIMILAR_TAGS -> "duplicateReasons.similarTags"
    DuplicateReason.SIMILAR_COLOR -> "duplicateReasons.similarColor"
    DuplicateReason.SAME_SIZE -> "duplicateReasons.sameSize"
    DuplicateReason.OVERALL_SIMILARITY -> "duplicateReasons.overallSimilarity"
}

internal fun ColorRelationship.tsKey(): String = when (this) {
    ColorRelationship.UNKNOWN -> "unknown"
    ColorRelationship.SAME -> "same"
    ColorRelationship.NEUTRAL -> "neutral"
    ColorRelationship.ANALOGOUS -> "analogous"
    ColorRelationship.NEAR_MISS -> "near-miss"
    ColorRelationship.CONTRASTING -> "contrasting"
}
