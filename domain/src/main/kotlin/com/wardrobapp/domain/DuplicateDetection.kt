package com.wardrobapp.domain

/**
 * Duplicate detection.
 *
 * Pure domain logic: the caller supplies what to compare against, so this has no
 * database dependency and can be tested alone.
 */

data class DuplicateMatch(
    val garment: Garment,
    val score: Double,
    val reasons: List<DuplicateReason>,
)

/**
 * Why a garment was reported. Modelled as a type rather than i18n keys so the
 * domain layer does not decide how anything is worded.
 */
enum class DuplicateReason {
    SIMILAR_TAGS,
    SIMILAR_COLOR,
    SAME_SIZE,

    /** Nothing stood out on its own, but the blend cleared the bar. */
    OVERALL_SIMILARITY,
}

/** The subset of a garment duplicate detection actually compares. */
data class DuplicateCandidate(
    val category: String,
    val subcategories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val colorPrimary: String,
    val colorPalette: List<String> = emptyList(),
    val size: String? = null,
)

/**
 * Score above which a garment is reported as a likely duplicate.
 *
 * Lower than the original 0.81 because the score is a weighted *average* over
 * the signals that have data, rather than a sum whose maximum depended on how
 * much the user happened to fill in. At 0.81 an exact duplicate with no tags
 * peaked at 0.40 and could never be reported at all.
 */
const val DUPLICATE_THRESHOLD = 0.65

/** One contribution to a duplicate score; a null score means "no data". */
private data class SignalTerm(val weight: Double, val score: Double?)

/**
 * Blend the signals that have something to say, ignoring the ones that do not.
 *
 * Weighting absent data as zero is what made the old score unreachable: with no
 * tags recorded, the tag term contributed nothing but still consumed 0.6 of the
 * available weight, capping an exact duplicate at 0.40 against a 0.81 threshold.
 * Renormalising over the active terms means an unanswered question lowers
 * confidence rather than arguing against a match.
 */
private fun weightedAverage(terms: List<SignalTerm>): Double? {
    val active = terms.filter { it.score != null }
    val totalWeight = active.sumOf { it.weight }
    if (totalWeight == 0.0) return null

    return active.sumOf { it.weight * it.score!! } / totalWeight
}

/**
 * Whether two garments are the same kind of thing.
 *
 * Sharing one is enough: a garment can carry more than one type, and two shirts
 * both filed under "T-Shirt" are the same kind of thing whatever else either of
 * them is also filed under.
 *
 * **Neither side may be empty.** A garment whose type was never recorded is not a
 * duplicate of anything, including another garment with no type: without knowing
 * what a garment is, there is no claim to make about it being the same as
 * something else. The cost is that such garments never appear, and nothing says
 * so -- accepted deliberately over the alternative of matching them on colour
 * alone, which is the comparison this whole change exists to stop making.
 */
private fun sharesSubcategory(here: List<String>, there: List<String>): Boolean {
    val ours = here.mapNotNullTo(mutableSetOf()) { it.trim().lowercase().ifEmpty { null } }
    if (ours.isEmpty()) return false

    return there.any { it.trim().lowercase().let { other -> other.isNotEmpty() && other in ours } }
}

/**
 * Whether two garments are the same colour.
 *
 * [ColorRelationship.SAME] rather than an equal hex string, and the difference
 * matters: colours are read off photographs, so two identical black shirts
 * photographed on different days are `#000000` and `#0A0A0A`. Demanding equality
 * would miss exactly the duplicates worth finding, where a deltaE under five is
 * the same colour to an eye.
 *
 * Anything unparseable, or the multi-colour sentinel, answers `UNKNOWN` and so
 * fails this -- which is the right reading of "must be the same colour" when the
 * colour is not known.
 */
private fun sameColor(here: String, there: String): Boolean =
    colorRelationship(here, there) == ColorRelationship.SAME

/**
 * Score a candidate against garments already in the wardrobe.
 *
 * Pure: the caller supplies what to compare against. Returns matches scoring
 * strictly above the threshold, highest first.
 */
fun findDuplicatesAmong(
    newGarment: DuplicateCandidate,
    existing: List<Garment>,
    threshold: Double = DUPLICATE_THRESHOLD,
): List<DuplicateMatch> {
    val matches = mutableListOf<DuplicateMatch>()

    for (garment in existing) {
        // Two conditions before any of the scoring, and they are conditions rather
        // than evidence: a garment failing either is not a near-duplicate however
        // well the rest of it scores. Weighing them instead is what produced the
        // wardrobe-wide nonsense -- the score renormalises over whatever has data,
        // so a black t-shirt and a black jumper with no tags scored exactly 1.0,
        // and no threshold below 1.0 could ever have excluded them.
        if (!sharesSubcategory(newGarment.subcategories, garment.effectiveSubcategories)) continue
        if (!sameColor(newGarment.colorPrimary, garment.primaryColor)) continue

        // Compare primary against primary. Taking the best match across the whole
        // palette cross-product meant any shared entry pinned this to 1.0 -- and
        // '#000000' is the schema default, so a red garment and a blue one that
        // both happened to list black scored as identical in colour.
        val colorSim = colorSimilarity(newGarment.colorPrimary, garment.primaryColor)

        val bothSizesKnown = !newGarment.size.isNullOrBlank() && !garment.size.isNullOrBlank()
        val sizeMatch = if (bothSizesKnown) {
            if (newGarment.size!!.trim().lowercase() == garment.size!!.trim().lowercase()) 1.0 else 0.0
        } else {
            null
        }

        val tagSim = jaccardSimilarity(newGarment.tags, garment.tags)

        val score = weightedAverage(
            listOf(
                SignalTerm(0.6, tagSim),
                SignalTerm(0.3, colorSim),
                SignalTerm(0.1, sizeMatch),
            )
        )

        if (score == null || score <= threshold) continue

        // No SIMILAR_COLOR, and its absence is deliberate. Colour is now the gate
        // above, so it holds of everything that reaches here: saying it would be
        // like explaining that they are all garments. What is left is what varies.
        val reasons = buildList {
            if (tagSim != null && tagSim > 0.5) add(DuplicateReason.SIMILAR_TAGS)
            if (sizeMatch == 1.0) add(DuplicateReason.SAME_SIZE)
            if (isEmpty()) add(DuplicateReason.OVERALL_SIMILARITY)
        }

        matches.add(DuplicateMatch(garment, score, reasons))
    }

    return matches.sortedByDescending { it.score }
}

/**
 * A garment as [findDuplicatesAmong] compares it.
 *
 * `primaryColor` rather than `colorPrimary`, so a garment compared against the
 * wardrobe is compared by the same colour a garment being *added* is: the form
 * builds its candidate from the head of the palette, and the two would otherwise
 * disagree about a garment whose palette leads with something other than the
 * stored primary.
 */
fun Garment.asDuplicateCandidate() = DuplicateCandidate(
    category = category,
    subcategories = effectiveSubcategories,
    tags = tags,
    colorPrimary = primaryColor,
    colorPalette = palette,
    size = size,
)

/**
 * Garments that look like each other, gathered.
 *
 * The anchor is first, and [reasons] is what *every* other member shares with it
 * -- so a heading can say "similar colour, same size" and be true of the whole
 * group rather than of one pair inside it.
 */
data class DuplicateGroup(
    val garments: List<Garment>,
    val reasons: List<DuplicateReason>,
)

/**
 * Sweep a wardrobe for garments that are much the same as each other.
 *
 * The other direction from [findDuplicatesAmong], which asks whether one garment
 * is already owned. This asks what is like what, over garments already saved --
 * five black t-shirts added over a year have never once been compared, because
 * the only thing that ever asked was the add form, at the moment of adding.
 *
 * **Groups are anchored, not chained.** Take the first garment nothing has claimed
 * yet, gather everything scoring above [threshold] against *it*, set them all
 * aside, repeat. A resembling B and B resembling C does not make A resemble C, so
 * a transitive closure would run a black tee to a grey tee to a grey jumper and
 * offer the three as one garment. A group here means "these are all like this
 * one", which is how somebody reads it -- and one absurd group is all it takes for
 * the whole list to stop being believed.
 *
 * **Category buckets first, and that is not tidiness.** [findDuplicatesAmong]
 * scores tags, colour and size; it never looks at `category` at all. It is right
 * today only because [findDuplicatesAmong]'s caller in :data filters the *query*
 * by category. Doing the bucketing here means the trap is shut on this side, where
 * a sweep that forgot would compare socks against shirts and call them identical.
 */
fun duplicateGroups(
    garments: List<Garment>,
    threshold: Double = DUPLICATE_THRESHOLD,
): List<DuplicateGroup> = garments
    .groupBy { it.category }
    .values
    .flatMap { groupsWithinOneCategory(it, threshold) }

private fun groupsWithinOneCategory(
    garments: List<Garment>,
    threshold: Double,
): List<DuplicateGroup> {
    val groups = mutableListOf<DuplicateGroup>()

    // Order is the caller's, and stays it: the same wardrobe has to produce the
    // same groups twice running, or a list that reshuffles itself between visits
    // looks like it is finding different things each time.
    var remaining = garments

    while (remaining.isNotEmpty()) {
        val anchor = remaining.first()
        val rest = remaining.drop(1)

        val matches = findDuplicatesAmong(anchor.asDuplicateCandidate(), rest, threshold)

        if (matches.isEmpty()) {
            // Nothing is like it. It cannot belong to a later group either, since
            // that group's anchor would have had to match it here.
            remaining = rest
            continue
        }

        val members = matches.map { it.garment }

        groups += DuplicateGroup(
            garments = listOf(anchor) + members,
            // What holds for the whole group. A reason true of one pair and not
            // the others would be a heading that lies about most of what is
            // under it, so an intersection rather than a union -- and when they
            // agree on nothing nameable, the honest answer is the one
            // `findDuplicatesAmong` already gives for that case.
            reasons = matches
                .map { it.reasons.toSet() }
                .reduce { shared, next -> shared intersect next }
                .toList()
                .ifEmpty { listOf(DuplicateReason.OVERALL_SIMILARITY) },
        )

        val claimed = members.mapTo(mutableSetOf()) { it.id }
        remaining = rest.filterNot { it.id in claimed }
    }

    return groups
}
