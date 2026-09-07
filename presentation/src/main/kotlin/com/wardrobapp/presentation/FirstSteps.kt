package com.wardrobapp.presentation

/**
 * The jobs the first-launch flow deliberately does not do.
 *
 * The flow explains; this is the part that has to be *done*, and it belongs where
 * it can be acted on rather than on a screen somebody is trying to get past. So
 * it is a card at the top of Home carrying one row per job, each of which ticks
 * itself off when the wardrobe says so.
 *
 * Nothing here is stored except [BULK_ADD]: the other two are questions the
 * wardrobe already answers, and a second record of an answer is a second thing
 * to keep in step. See [firstStepsFor].
 */
enum class FirstStep {
    /** Add one garment, from the camera or the gallery. */
    GARMENT,

    /** Add several at once, which is how a wardrobe that already exists gets in. */
    BULK_ADD,

    /** Rate an outfit idea, which is the only thing the suggestions learn from. */
    RATE,
}

/**
 * How many garments one bulk-add session has to produce for its row to tick.
 *
 * Two rather than one, because one garment through the bulk screen is the same
 * job the first row already covers -- the row is about the *drawerful*, and
 * ticking it for a single photo would tell somebody they had done something they
 * have not tried.
 */
const val BULK_ADD_MINIMUM = 2

/**
 * The card's state: which rows are ticked, and whether it belongs on Home at all.
 *
 * A type rather than a bare `Set` so that "should this be on screen" has one
 * answer that both the screen and the flag-writing agree on -- they disagreed
 * once, and a card that vanished but came back after a restart is worse than one
 * that never went.
 */
data class FirstSteps(
    val done: Set<FirstStep>,
    /** The reader dismissed it, or a restore did. Permanent; there is no undo. */
    val dismissed: Boolean,
) {
    /** Every job done. Nothing left to come back for. */
    val isComplete: Boolean get() = done.containsAll(FirstStep.entries)

    /** Whether the card belongs at the top of Home. */
    val isVisible: Boolean get() = !dismissed && !isComplete

    fun isDone(step: FirstStep): Boolean = step in done
}

/**
 * What the card should say, from what the wardrobe holds.
 *
 * [garments] and [ratedOutfits] are nullable because "not read yet" and "none"
 * are different answers and only one of them is a fact: a row that ticked itself
 * off a failed read would tell somebody they had added a garment they have not,
 * and one that *un*-ticked would be worse. Null leaves the row alone, which is
 * the same rule the counts on Home already follow.
 *
 * [bulkAddUsed] is the one flag with nothing behind it in the data -- a bulk
 * session leaves ordinary garment rows and no trace of how they arrived -- so it
 * is passed in from where it is stored.
 */
fun firstStepsFor(
    dismissed: Boolean,
    garments: Long?,
    bulkAddUsed: Boolean,
    ratedOutfits: Long?,
): FirstSteps = FirstSteps(
    done = buildSet {
        if (garments != null && garments > 0) add(FirstStep.GARMENT)
        if (bulkAddUsed) add(FirstStep.BULK_ADD)
        if (ratedOutfits != null && ratedOutfits > 0) add(FirstStep.RATE)
    },
    dismissed = dismissed,
)
