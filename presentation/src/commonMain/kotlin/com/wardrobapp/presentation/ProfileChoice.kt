package com.wardrobapp.presentation

/**
 * Which profile the browser opens on its own, or null to ask.
 *
 *  - A Home Assistant user who has made a profile theirs gets that one,
 *    every time -- switching to another in Settings lasts as long as the
 *    page, so a shared tablet does not leave the next person in somebody
 *    else's wardrobe.
 *  - A Home Assistant user who has not is asked, once: which is yours, or
 *    make a new one. After an upgrade that is everybody, and the answer
 *    cannot be guessed -- the wardrobe that was there belongs to whoever
 *    built it, not to whoever opens the page first.
 *  - Nobody signed in -- a development server, answering a browser directly
 *    -- has no "yours", so the last profile this browser opened, or the only
 *    one there is.
 */
fun profileToOpen(ids: List<String>, yours: String?, signedIn: Boolean, lastOpened: String?): String? = when {
    yours != null && yours in ids -> yours
    signedIn -> null
    lastOpened != null && lastOpened in ids -> lastOpened
    else -> ids.singleOrNull()
}
