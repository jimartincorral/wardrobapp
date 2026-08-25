package com.wardrobapp.data

/** An outfit as the database holds it. */
data class OutfitRecord(
    val id: String,
    val name: String,
    val garmentIds: List<String>,
    val occasion: String?,
    val season: String?,
    val createdAt: String?,
    val isSuggested: Boolean,
    /**
     * Kept for what it teaches, not for wearing.
     *
     * An outfit that was rated but not kept: the rating has already moved the
     * learned pair scores, and throwing the outfit away would leave those scores
     * with nothing behind them -- no way to see what taught them, and no way to
     * correct a rating given by mistake. So it stays, out of the way.
     */
    val isArchived: Boolean = false,
    val isPinned: Boolean,
)

/** A rating as the database holds it. An outfit carries at most one. */
data class RatingRecord(
    val id: String,
    val outfitId: String,
    val rating: Int,
    val feedback: String?,
    val ratedAt: String?,
)
