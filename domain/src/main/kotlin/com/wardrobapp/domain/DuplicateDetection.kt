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
)

/**
 * Score above which a garment is reported as a likely duplicate.
 *
 * It was 0.81 once, then 0.65, then 0.74. The move down was because the score is a
 * weighted *average* over whichever signals have data rather than a sum: at 0.81 an
 * exact duplicate with no tags peaked at 0.40 and could never fire at all.
 *
 * 0.74 is not a taste. Past the subcategory and palette gates the only thing left
 * to disagree about is tags, and what that produces is a short list:
 *
 * ```
 *   no tags recorded on either            0.819 to 1.000, by how close the colours are
 *   identical tags                        1.000
 *   tags 3 of 4 shared                    0.733
 *   tags 2 of 3 shared                    0.667
 * ```
 *
 * The bar sits in the gap between sharing most of your tags and sharing all of
 * them. Anything from 0.734 to 0.819 behaves identically; 0.74 is that band's
 * near edge, kept from when the number had a tighter constraint to satisfy.
 *
 * What it removes is partial tag overlap -- two summer cotton navy tops are a pair
 * of tops, not one top twice. What it keeps is a garment whose tags agree, and one
 * with no tags at all, which the gates alone have already found to be the same kind
 * of thing in the same colours.
 */
const val DUPLICATE_THRESHOLD = 0.74

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
 * matters for anything not read off a photograph: a hand-entered or imported
 * colour need not land on a palette entry, and a deltaE under five is the same
 * colour to an eye.
 *
 * Anything unparseable, or the multi-colour sentinel, answers `UNKNOWN` and so
 * fails this -- which is the right reading of "must be the same colour" when the
 * colour is not known.
 */
private fun sameColor(here: String, there: String): Boolean =
    colorRelationship(here, there) == ColorRelationship.SAME

/**
 * Whether two garments are the same colours -- all of them.
 *
 * A black and red shirt is not a red shirt. Comparing only the dominant colour
 * said it was, because the dominant colour of both is red and nothing looked
 * further, which is how a two-colour garment came to be reported as a duplicate
 * of a plain one.
 *
 * So the palettes have to correspond: the same number of colours, each with a
 * partner in the other. Cardinality is a fair thing to insist on here rather than
 * an accident of extraction -- `dominantGarmentColors` returns at most two, snaps
 * them to named palette entries, and only admits a second when it covers enough
 * of the garment to be worth calling a colour. A second entry means the garment
 * really has two.
 *
 * Order is not part of it. Which of two colours dominates can differ between two
 * photographs of one garment, and that is a fact about the photographs.
 */
private fun paletteSimilarity(here: List<String>, there: List<String>): Double? {
    if (here.size != there.size || here.isEmpty()) return null

    // Each colour claims a partner, and a claimed one is spent: without that, a
    // garment in two shades of red would match one that is red twice over.
    val unclaimed = there.toMutableList()
    var weakest = 1.0

    for (colour in here) {
        val match = unclaimed.indexOfFirst { sameColor(colour, it) }
        if (match < 0) return null

        // The weakest pair, not the average: how alike two garments' colours are
        // is how alike the least alike of them are, and averaging would let a
        // perfect match carry a barely-passing one.
        weakest = minOf(weakest, colorSimilarity(colour, unclaimed.removeAt(match)))
    }

    return weakest
}

/** The colours to compare a candidate by, falling back as [Garment.palette] does. */
private val DuplicateCandidate.comparedColors: List<String>
    get() = colorPalette.filter { it.isNotBlank() }.ifEmpty {
        listOf(colorPrimary).filter { it.isNotBlank() }
    }

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
        // Null when the palettes do not correspond, which is the gate; otherwise
        // how alike the least alike of the matched pairs are, which is the signal.
        // One call for both, because a gate and a score that disagreed about which
        // colours were being compared is exactly the bug this replaced: the gate
        // matched a reversed palette while the score read red against black.
        val colorSim = paletteSimilarity(newGarment.comparedColors, garment.palette) ?: continue

        val tagSim = jaccardSimilarity(newGarment.tags, garment.tags)

        // No size term. A size is what fits you rather than what a garment is:
        // the same shirt in an M and an L is the same shirt, and two different
        // shirts that happen to both be M are still two shirts. It said nothing
        // either way, so it is gone rather than reweighted.
        val score = weightedAverage(
            listOf(
                SignalTerm(0.6, tagSim),
                SignalTerm(0.3, colorSim),
            )
        )

        if (score == null || score <= threshold) continue

        // No SIMILAR_COLOR, and its absence is deliberate. Colour is now the gate
        // above, so it holds of everything that reaches here: saying it would be
        // like explaining that they are all garments. What is left is what varies.
        val reasons = buildList {
            if (tagSim != null && tagSim > 0.5) add(DuplicateReason.SIMILAR_TAGS)
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
)

/**
 * Garments that look like each other, gathered.
 *
 * The anchor is first, and [reasons] is what *every* other member shares with it
 * -- so a heading is true of the whole group rather than of one pair inside it.
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
 * scores tags and colour; it never looks at `category` at all. It is right
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
