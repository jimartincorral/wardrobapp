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
