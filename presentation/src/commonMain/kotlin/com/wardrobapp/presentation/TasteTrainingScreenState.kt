package com.wardrobapp.presentation

import com.wardrobapp.presentation.OutfitsScreenState.Suggestion

/**
 * What the training screen is given to draw: the round, where in it the reader
 * is, and what the session has taught so far.
 *
 * One round at a time, held whole: the engine is asked once per round (see
 * TasteTrainingScreenModel), and the screen shows [current] and the count. The
 * tallies are the session's, not the wardrobe's -- what the summary says at
 * the end of a round is what this sitting did, and Home's own count catches up
 * on return.
 */
data class TasteTrainingScreenState(
    val round: List<Suggestion> = emptyList(),
    /** Index into [round] of the outfit on screen; past the end once the round is done. */
    val position: Int = 0,
    /** A round being asked for. */
    val loading: Boolean = false,
    /** A rating on its way to the source; the stars wait for it, so a double tap cannot rate twice. */
    val writing: Boolean = false,
    val rated: Int = 0,
    /** Rated [TasteTrainingScreenModel.LIKED_FROM] stars or more. */
    val liked: Int = 0,
    /** Rated [TasteTrainingScreenModel.DISLIKED_TO] stars or fewer. */
    val disliked: Int = 0,
    val skipped: Int = 0,
    /** Every outfit shown this session, as garment ids: what the next round is asked to go beyond. */
    val shown: List<List<String>> = emptyList(),
    /** A round after the first came back empty: the engine has nothing more to offer, and Keep going would too. */
    val exhausted: Boolean = false,
    /** True once a round has been asked for, so "none" can differ from "not yet". */
    val hasFetched: Boolean = false,
    val error: String? = null,
) {
    /** The outfit on screen, or null between rounds and at the end of one. */
    val current: Suggestion? get() = round.getOrNull(position)

    /** The round is over: everything in it was rated or skipped, and nothing is on its way. */
    val isRoundFinished: Boolean get() = hasFetched && !loading && current == null

    /**
     * The first round had nothing in it at all: the engine could not build an
     * outfit from the wardrobe. Not a later one that came back empty -- that
     * is [exhausted], and the summary says so over the session's tallies.
     */
    val isEmpty: Boolean get() = hasFetched && !loading && round.isEmpty() && !exhausted
}
