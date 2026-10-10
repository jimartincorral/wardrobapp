package com.wardrobapp.domain

import kotlin.math.sqrt

/**
 * What a reader's taste looks like as a point in the style model's space, and
 * how close an outfit comes to it.
 *
 * The Home Assistant app embeds every garment photo with an image model (see
 * the server's StyleEncoder): a vector that is near another garment's when
 * the two look alike in the ways the model was trained to notice, which for
 * clothes is cut, fabric, formality and colour together. A photo of a look
 * the reader likes embeds into the same space. So "outfits like the ones I
 * like" has an arithmetic: add up the looks they saved and the outfits they
 * rated well, take away the outfits they rated badly, and score an outfit by
 * how close its garments' average sits to that sum. It is the one term of the
 * score that reaches garments nobody has rated and that a photo from outside
 * the wardrobe can teach.
 *
 * Vectors arrive unit length, as the encoder stores them, and every answer
 * here is a cosine, so the numbers are the same whatever the model's scale.
 */

/** Who to ask for a garment's vector; null for a garment the model has not seen. */
fun interface StyleLookup {
    fun vector(garmentId: String): FloatArray?
}

/**
 * The reader's taste: the saved looks and the rated outfits, weighted and
 * summed, as a unit vector. Null when there is nothing to say -- no looks
 * and no ratings, or vectors that cancel to nothing.
 *
 * An inspiration photo counts as a full like. A rated outfit counts by its
 * rating, -1 to +1, applied to the mean of its garments: four stars is a
 * nudge, one star pulls away. A rated outfit is one data point however
 * many garments are in it, which the mean is for.
 */
fun tasteVector(
    inspirations: List<FloatArray>,
    ratedOutfits: List<Pair<List<FloatArray>, Int>>,
): FloatArray? {
    val dimensions = inspirations.firstOrNull()?.size
        ?: ratedOutfits.firstOrNull { it.first.isNotEmpty() }?.first?.first()?.size
        ?: return null
    val sum = FloatArray(dimensions)

    for (look in inspirations) addScaled(sum, look, 1f)
    for ((garments, rating) in ratedOutfits) {
        val mean = meanOf(garments) ?: continue
        addScaled(sum, mean, normalizeRating(rating).toFloat())
    }

    return normalized(sum)
}

/**
 * How close an outfit of these [garments] comes to [taste]: the cosine of
 * its garments' mean, -1 to 1. Garments the model has not seen are left out
 * of the mean; an outfit with none seen scores 0, which is "no opinion".
 */
fun styleScore(garments: List<FloatArray?>, taste: FloatArray): Double {
    val mean = meanOf(garments.filterNotNull()) ?: return 0.0
    val unit = normalized(mean) ?: return 0.0
    return dot(unit, taste).toDouble()
}

private fun meanOf(vectors: List<FloatArray>): FloatArray? {
    if (vectors.isEmpty()) return null
    val mean = FloatArray(vectors.first().size)
    for (vector in vectors) addScaled(mean, vector, 1f / vectors.size)
    return mean
}

private fun addScaled(into: FloatArray, vector: FloatArray, scale: Float) {
    for (i in into.indices) into[i] += vector[i] * scale
}

private fun dot(a: FloatArray, b: FloatArray): Float {
    var total = 0f
    for (i in a.indices) total += a[i] * b[i]
    return total
}

/** [vector] at unit length, or null for the zero vector, which has no direction. */
fun normalized(vector: FloatArray): FloatArray? {
    val length = sqrt(dot(vector, vector))
    if (length <= 1e-12f) return null
    return FloatArray(vector.size) { vector[it] / length }
}
