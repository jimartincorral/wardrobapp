package com.wardrobapp.presentation

/*
 * Training the suggestions: a session of rating outfits for its own sake.
 *
 * The engine learns from ratings and from nothing else (see PairLearning and
 * PreferenceLearning in :domain), and nothing in the app asked for one: the
 * outfits tab showed three ideas and hoped. A new wardrobe therefore got
 * cold-start suggestions for weeks, which is the thing most likely to make
 * somebody decide the ideas are no good and stop looking. This is the ask: ten
 * outfits in a row, one at a time, each rated with the same five stars and the
 * same learning as a rating on the outfits tab, then ten more if they like.
 *
 * Built on OutfitsSource rather than a source of its own, because the three
 * things a session does -- ask for outfits, rate one, keep one -- are already
 * on it and already implemented for the phone, the server and the browser.
 * What training wants that the tab does not is breadth, and that is one field
 * on the request (SuggestionRequest.explore).
 */

/** How many outfits a round asks for. Ten: short enough to finish, enough to teach something. */
const val TRAINING_ROUND_SIZE = 10

/**
 * Whether Home should offer training, from what the wardrobe holds.
 *
 * Not while the first-steps card is up: its "rate an outfit" row opens training
 * already, and two entries to one place is one too many. Then only while the
 * wardrobe has fewer ratings than [TRAINED_RATINGS] -- one round's worth, after
 * which the suggestions have something to go on and the offer would be nagging
 * -- and at least [TRAINABLE_GARMENTS] garments, below which the engine cannot
 * fill a template and a session would open onto "no outfits could be built".
 * Null counts are "not read yet", which is never a reason to show a card, the
 * rule firstStepsFor follows too. The outfits tab offers it regardless.
 */
fun showsTasteTraining(firstStepsVisible: Boolean, garments: Long?, rated: Long?): Boolean =
    !firstStepsVisible &&
        garments != null && garments >= TRAINABLE_GARMENTS &&
        rated != null && rated < TRAINED_RATINGS

/** Ratings enough for Home to stop offering training. */
const val TRAINED_RATINGS = 10L

/** Garments enough for the engine to have something to put together. */
const val TRAINABLE_GARMENTS = 4L
