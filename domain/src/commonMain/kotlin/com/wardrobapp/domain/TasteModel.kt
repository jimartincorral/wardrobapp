package com.wardrobapp.domain

/**
 * What the reader's ratings say about outfits in general, as weights on
 * [OutfitFeature]s.
 *
 * Fitted from every rating at once rather than nudged by each as it arrives.
 * The pair and garment scores are running averages, and a running average
 * depends on the order the ratings came in -- which, after a sync, is not the
 * order they were given in; the two sides of a sync end up with slightly
 * different opinions of the same ratings. A fit over the whole history has one
 * answer whatever the order, and the history is small: a few hundred rows,
 * fourteen numbers each, which is nothing to refit every time outfits are
 * asked for.
 *
 * Ridge regression: least squares with the weights pulled towards zero by
 * [RIDGE_LAMBDA]. The pull is what keeps three ratings from becoming a strong
 * opinion -- with few examples the fit stays near zero and the engine's own
 * judgement decides; with many, the examples win. It is also what makes the
 * normal equations solvable when two features move together, as the harmony
 * shares do.
 *
 * The prediction is in the units of a normalised rating, -1 (one star) to +1
 * (five), and it is the *difference* between outfits that matters: the bias
 * carries the reader's average and cancels out of a ranking.
 */
class TasteModel(val weights: DoubleArray) {

    /** What this reader would think of an outfit with these [features], -1..1 or thereabouts. */
    fun score(features: DoubleArray): Double {
        var total = 0.0
        for (i in weights.indices) total += weights[i] * features[i]
        return total
    }

    companion object {
        /** How hard the weights are pulled towards zero; see the class comment. */
        const val RIDGE_LAMBDA = 2.0

        /** Under this many ratings nothing is fitted: a line through two points is not a taste. */
        const val MIN_EXAMPLES = 5
    }
}

/** One rated outfit, as the model sees it. */
class RatedExample(val features: DoubleArray, val rating: Int)

/**
 * Fit a [TasteModel] to [examples], or null while there are too few to mean
 * anything.
 *
 * Solves (XᵀX + λI) w = Xᵀy by Gaussian elimination with partial pivoting, on
 * a matrix the size of the feature count: no library, no allocation worth
 * mentioning. The bias column is penalised like the rest, which pulls it
 * towards zero rather than towards the mean rating; that costs nothing a
 * ranking can see, and keeps one rule for every weight.
 */
fun fitTasteModel(examples: List<RatedExample>, lambda: Double = TasteModel.RIDGE_LAMBDA): TasteModel? {
    if (examples.size < TasteModel.MIN_EXAMPLES) return null

    val n = OutfitFeature.COUNT
    val a = Array(n) { DoubleArray(n) }
    val b = DoubleArray(n)

    for (example in examples) {
        val x = example.features
        val y = normalizeRating(example.rating)
        for (i in 0 until n) {
            b[i] += x[i] * y
            for (j in 0 until n) a[i][j] += x[i] * x[j]
        }
    }
    for (i in 0 until n) a[i][i] += lambda

    // Forward elimination with the largest pivot in each column, then back
    // substitution. The ridge term keeps every pivot away from zero.
    for (column in 0 until n) {
        var pivot = column
        for (row in column + 1 until n) {
            if (kotlin.math.abs(a[row][column]) > kotlin.math.abs(a[pivot][column])) pivot = row
        }
        if (pivot != column) {
            val swap = a[pivot]; a[pivot] = a[column]; a[column] = swap
            val swapB = b[pivot]; b[pivot] = b[column]; b[column] = swapB
        }
        for (row in column + 1 until n) {
            val factor = a[row][column] / a[column][column]
            if (factor == 0.0) continue
            for (k in column until n) a[row][k] -= factor * a[column][k]
            b[row] -= factor * b[column]
        }
    }
    val weights = DoubleArray(n)
    for (i in n - 1 downTo 0) {
        var sum = b[i]
        for (k in i + 1 until n) sum -= a[i][k] * weights[k]
        weights[i] = sum / a[i][i]
    }
    return TasteModel(weights)
}
