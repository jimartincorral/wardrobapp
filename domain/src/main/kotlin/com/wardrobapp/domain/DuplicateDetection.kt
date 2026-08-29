package com.wardrobapp.domain

/**
 * Duplicate detection.
 *
 * Two garments are the same garment when they are the same kind of thing in the
 * same colours. That is the whole rule, and it is a rule rather than a score on
 * purpose.
 *
 * There used to be a weighted average over tags, colour and size, renormalised
 * over whichever of them had data, and a threshold that moved three times trying
 * to make it behave. Every move made it harder to say what the app thought a
 * duplicate was, and none of them fixed the complaint, because the complaints were
 * never about degree: a jumper is not a t-shirt, a black and red shirt is not a red
 * shirt, and a size is what fits you rather than what a garment is. Those are
 * categorical, and a number cannot express them. So the numbers are gone.
 *
 * What this costs, stated plainly: every navy t-shirt in a wardrobe is now one
 * group, whatever else distinguishes them. That is what the rule says, and its
 * virtue is that a reader can predict it without knowing any of this.
 *
 * Pure domain logic: the caller supplies what to compare against, so this has no
 * database dependency and can be tested alone.
 */

data class DuplicateMatch(val garment: Garment)

/** The subset of a garment duplicate detection actually compares. */
data class DuplicateCandidate(
    val category: String,
    val subcategories: List<String> = emptyList(),
    val colorPrimary: String,
    val colorPalette: List<String> = emptyList(),
)

/**
 * Whether two garments are the same kind of thing.
 *
 * Sharing one type is enough: a garment can carry more than one, and two shirts
 * both filed under "T-Shirt" are the same kind of thing whatever else either of
 * them is also filed under.
 *
 * **Neither side may be empty.** A garment whose type was never recorded is not a
 * duplicate of anything, including another garment with no type: without knowing
 * what a garment is, there is no claim to make about it being the same as
 * something else. The cost is that such garments never appear, and nothing says
 * so.
 */
private fun sharesSubcategory(here: List<String>, there: List<String>): Boolean {
    val ours = here.mapNotNullTo(mutableSetOf()) { it.trim().lowercase().ifEmpty { null } }
    if (ours.isEmpty()) return false

    return there.any { it.trim().lowercase().let { other -> other.isNotEmpty() && other in ours } }
}

/**
 * Whether two colours are the same colour.
 *
 * [ColorRelationship.SAME] rather than an equal hex string: a hand-entered or
 * imported colour need not land on a palette entry, and a deltaE under five is the
 * same colour to an eye. Anything unparseable, or the multi-colour sentinel,
 * answers `UNKNOWN` and so fails -- which is the right reading of "the same
 * colour" when the colour is not known.
 */
private fun sameColor(here: String, there: String): Boolean =
    colorRelationship(here, there) == ColorRelationship.SAME

/**
 * Whether two garments are the same colours -- all of them.
 *
 * A black and red shirt is not a red shirt. The palettes have to correspond: the
 * same number of colours, each with a partner in the other, and a partner spent
 * once claimed so a garment in two shades of red cannot match one that is red
 * twice over.
 *
 * Cardinality is fair to insist on rather than an accident of extraction:
 * `dominantGarmentColors` returns at most two, snaps them to named palette entries,
 * and admits a second only when it covers enough of the garment to be worth calling
 * a colour. A second entry means the garment really has two.
 *
 * Order is not part of it. Which of two colours dominates can differ between two
 * photographs of one garment, and that is a fact about the photographs.
 */
private fun samePalette(here: List<String>, there: List<String>): Boolean {
    if (here.size != there.size || here.isEmpty()) return false

    val unclaimed = there.toMutableList()

    return here.all { colour ->
        val match = unclaimed.indexOfFirst { sameColor(colour, it) }
        if (match < 0) false else { unclaimed.removeAt(match); true }
    }
}

/** The colours to compare a candidate by, falling back as [Garment.palette] does. */
private val DuplicateCandidate.comparedColors: List<String>
    get() = colorPalette.filter { it.isNotBlank() }.ifEmpty {
        listOf(colorPrimary).filter { it.isNotBlank() }
    }

/**
 * The garments a candidate is already one of.
 *
 * Pure: the caller supplies what to compare against, and which garments are even
 * considered -- in use only, and it is :data that knows that.
 *
 * In the order given rather than ranked. There is nothing left to rank by: every
 * match satisfies the same rule to the same degree, and inventing an order would
 * imply one is more of a duplicate than another.
 */
fun findDuplicatesAmong(
    newGarment: DuplicateCandidate,
    existing: List<Garment>,
): List<DuplicateMatch> = existing
    .filter {
        sharesSubcategory(newGarment.subcategories, it.effectiveSubcategories) &&
            samePalette(newGarment.comparedColors, it.palette)
    }
    .map { DuplicateMatch(it) }

/** A garment as [findDuplicatesAmong] compares it. */
fun Garment.asDuplicateCandidate() = DuplicateCandidate(
    category = category,
    subcategories = effectiveSubcategories,
    colorPrimary = primaryColor,
    colorPalette = palette,
)

/** Garments that are each other, the one they were gathered around first. */
data class DuplicateGroup(val garments: List<Garment>)

/**
 * Sweep a wardrobe for garments that are the same garment.
 *
 * The other direction from [findDuplicatesAmong], which asks whether one garment is
 * already owned. This asks what is what, over garments already saved -- five black
 * t-shirts added over a year have never once been compared, because the only thing
 * that ever asked was the add form, at the moment of adding.
 *
 * Take the first garment nothing has claimed, gather everything matching it, set
 * them all aside, repeat. Chaining is not a risk the way it was when this was a
 * score -- being the same type in the same colours is transitive, near enough --
 * but anchoring costs nothing and keeps the groups explicable.
 *
 * **Category buckets first, and that is not tidiness.** Two categories can share a
 * subcategory name, and comparing across them would put a child's "T-Shirt" in with
 * an adult's. Doing it here means :data cannot get it wrong.
 */
fun duplicateGroups(garments: List<Garment>): List<DuplicateGroup> = garments
    .groupBy { it.category }
    .values
    .flatMap { groupsWithinOneCategory(it) }

private fun groupsWithinOneCategory(garments: List<Garment>): List<DuplicateGroup> {
    val groups = mutableListOf<DuplicateGroup>()

    // Order is the caller's, and stays it: the same wardrobe has to produce the
    // same groups twice running, or a list that reshuffles itself between visits
    // looks like it is finding different things each time.
    var remaining = garments

    while (remaining.isNotEmpty()) {
        val anchor = remaining.first()
        val rest = remaining.drop(1)

        val members = findDuplicatesAmong(anchor.asDuplicateCandidate(), rest).map { it.garment }

        if (members.isEmpty()) {
            // Nothing is it. It cannot belong to a later group either, since that
            // group's anchor would have had to match it here.
            remaining = rest
            continue
        }

        groups += DuplicateGroup(listOf(anchor) + members)

        val claimed = members.mapTo(mutableSetOf()) { it.id }
        remaining = rest.filterNot { it.id in claimed }
    }

    return groups
}
