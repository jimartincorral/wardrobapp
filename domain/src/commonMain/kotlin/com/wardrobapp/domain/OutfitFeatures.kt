package com.wardrobapp.domain

import kotlin.math.abs

/**
 * An outfit as a row of numbers a model can learn from.
 *
 * The suggestion engine's own judgement is a handful of hand-weighted terms
 * (colour harmony, occasion, season). What it learned from ratings was, until
 * this, a memory of particular garments: this pair went well, that shirt is
 * liked. A memory like that carries nothing to the next outfit. These features
 * are the things one outfit has in common with another -- how formal it is on
 * average, how far its pieces disagree about that, how many of them are
 * patterned, which kinds of colour pairing it leans on -- so that a rating
 * teaches something about outfits rather than about garments.
 *
 * Every feature is scaled to roughly -1..1 so a learned weight is comparable to
 * another and a regularised fit (see TasteModel) treats them alike. The order
 * is [OutfitFeature]'s, which is what makes a weight addressable in a test and
 * a stored model meaningful across builds that agree on the enum.
 */
enum class OutfitFeature {
    /** Always one, so the model can hold the reader's average rating apart from the features. */
    BIAS,
    /** Mean formality, centred: -1 is all lounge, +1 all formal. */
    FORMALITY_MEAN,
    /** How far the most and least formal pieces disagree, 0..1. */
    FORMALITY_SPREAD,
    /** Patterned pieces beyond the first, 0..1. */
    PATTERNS,
    /** Whether two pieces are patterned, 0 or 1: the thing people mean by "clashing". */
    PATTERN_CLASH,
    /** Distinct loud colours beyond two, 0..1. */
    COLOUR_COUNT,
    /** Loud colours, 0..1. */
    LOUD_COLOURS,
    HARMONY_SAME,
    HARMONY_NEUTRAL,
    HARMONY_ANALOGOUS,
    HARMONY_NEAR_MISS,
    HARMONY_CONTRASTING,
    /** How differently the top and the bottom hang, 0..1: a fitted top over relaxed trousers scores high. */
    FIT_CONTRAST,
    /** How far the lightest and heaviest fabrics are apart, 0..1. */
    WEIGHT_MISMATCH,
    /** Statement pieces, 0..1, two or more counting as one. */
    STATEMENTS;

    companion object {
        val COUNT: Int = entries.size
    }
}

/** [garments] as the feature row [OutfitFeature] describes. */
fun outfitFeatures(garments: List<Garment>): DoubleArray {
    val features = DoubleArray(OutfitFeature.COUNT)
    if (garments.isEmpty()) return features

    val attributes = garments.map { it.attributes }
    val formality = attributes.map { it.formality!!.level }
    features[OutfitFeature.BIAS.ordinal] = 1.0
    features[OutfitFeature.FORMALITY_MEAN.ordinal] = (formality.average() - 3.0) / 2.0
    features[OutfitFeature.FORMALITY_SPREAD.ordinal] = (formality.max() - formality.min()) / 4.0

    val patterned = attributes.count { it.pattern != Pattern.SOLID }
    features[OutfitFeature.PATTERNS.ordinal] = ((patterned - 1).coerceAtLeast(0) / 3.0).coerceAtMost(1.0)
    features[OutfitFeature.PATTERN_CLASH.ordinal] = if (patterned >= 2) 1.0 else 0.0

    val colours = garments.map { it.primaryColor }
    val coloured = colours.filter { isLoudColor(it) }.map { it.trim().uppercase() }.distinct()
    features[OutfitFeature.COLOUR_COUNT.ordinal] = ((coloured.size - 2).coerceAtLeast(0) / 2.0).coerceAtMost(1.0)
    features[OutfitFeature.LOUD_COLOURS.ordinal] = (colours.count { isLoudColor(it) } / 3.0).coerceAtMost(1.0)

    // Share of pairs per relationship, so a three-piece and a five-piece outfit
    // that lean on the same kind of pairing read the same.
    var pairs = 0
    for (i in garments.indices) {
        for (j in i + 1 until garments.size) {
            pairs++
            val feature = when (colorRelationship(colours[i], colours[j])) {
                ColorRelationship.SAME -> OutfitFeature.HARMONY_SAME
                ColorRelationship.NEUTRAL -> OutfitFeature.HARMONY_NEUTRAL
                ColorRelationship.ANALOGOUS -> OutfitFeature.HARMONY_ANALOGOUS
                ColorRelationship.NEAR_MISS -> OutfitFeature.HARMONY_NEAR_MISS
                ColorRelationship.CONTRASTING -> OutfitFeature.HARMONY_CONTRASTING
                ColorRelationship.UNKNOWN -> null
            }
            if (feature != null) features[feature.ordinal] += 1.0
        }
    }
    if (pairs > 0) {
        for (feature in HARMONY_FEATURES) features[feature.ordinal] /= pairs
    }

    val top = garments.firstOrNull { it.category == "tops" || it.category == "dresses" }
    val bottom = garments.firstOrNull { it.category == "bottoms" }
    if (top != null && bottom != null) {
        features[OutfitFeature.FIT_CONTRAST.ordinal] =
            abs(top.attributes.fit!!.ordinal - bottom.attributes.fit!!.ordinal) / 3.0
    }

    val weights = attributes.map { it.weight!!.ordinal }
    features[OutfitFeature.WEIGHT_MISMATCH.ordinal] = (weights.max() - weights.min()) / 2.0

    features[OutfitFeature.STATEMENTS.ordinal] = (attributes.count { it.statement == true } / 2.0).coerceAtMost(1.0)

    return features
}

private val HARMONY_FEATURES = listOf(
    OutfitFeature.HARMONY_SAME,
    OutfitFeature.HARMONY_NEUTRAL,
    OutfitFeature.HARMONY_ANALOGOUS,
    OutfitFeature.HARMONY_NEAR_MISS,
    OutfitFeature.HARMONY_CONTRASTING,
)
