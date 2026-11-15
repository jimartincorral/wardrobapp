package com.wardrobapp.data

/**
 * Garment types that moved from one category to another, and the rows still
 * filed under the old one, brought along.
 *
 * The catalogue says which category offers which type (GarmentCatalogue in
 * :domain), and a garment stores the category it was saved under. Moving a
 * type in the catalogue moves where new garments of that type go; the ones
 * already saved would stay where they were, shown under a category whose form
 * no longer offers their type -- open one to edit and its type chip is gone.
 * So the rows are moved too.
 *
 * Not once, but every time it could matter, because once is not enough here
 * for the reason it was not enough for the tags (see StructuredTags in
 * :domain): a backup made before the move puts the old rows straight back, and
 * so does a phone or a Home Assistant app that has not been updated yet, the
 * next time it syncs. So this runs on every open of the database -- the open
 * that follows a restore included -- from [WardrobeSchema.applyTo], and after
 * every sync applies what it merged, from SyncStore. It is one UPDATE per move
 * that matches nothing once the rows are moved, so running it often costs
 * nothing.
 *
 * A moved row is stamped as changed, now, in SQLite's own clock and in the
 * shape every other timestamp has. That is what makes a sync carry the move:
 * by the rule sync keeps, the latest change wins, and an unstamped move would
 * tie with the copy on a device that has not moved it -- and lose half the
 * time. Stamped, the moved copy is the newer one, and it goes everywhere.
 *
 * Only a garment whose types are the moved type and nothing else is moved. One
 * filed as both a cardigan and a jacket is not wholly either, and moving it
 * would strand the other type in a category that does not offer it; it stays,
 * and somebody editing it chooses. Read the three ways a row can hold its
 * types: the JSON list every build since the first writes, the bare
 * comma-separated string the oldest rows have, and the single `subcategory`
 * column with an empty list beside it.
 */
internal object GarmentMoves {

    /** [type] used to be offered under [from], and is now under [to]. */
    data class Move(val type: String, val from: String, val to: String)

    /**
     * Every move there has been, oldest first.
     *
     * A cardigan is worn over a top and under a coat, which is what a mid-layer
     * is, and somebody looking for one there found nothing: it had been filed
     * under outerwear since the app this one replaced. The suggestion engine
     * treats the two categories as one slot (see OutfitSuggestions in
     * :domain), so no outfit changes for the move.
     */
    val MOVES: List<Move> = listOf(
        Move(type = "Cardigan", from = "outerwear", to = "midlayer"),
    )

    /** Now, as isoTimestamp writes it -- `2026-11-15T19:04:05.123Z` -- in SQLite's clock, which every driver has. */
    private const val NOW = "strftime('%Y-%m-%dT%H:%M:%fZ', 'now')"

    /** Move every row the moves still apply to; how many were moved. */
    fun applyTo(driver: SqlDriver): Int = MOVES.sumOf { move ->
        driver.execute(
            """
            UPDATE garments SET category = ?, updated_at = $NOW
            WHERE category = ?
              AND (
                subcategories IN (?, ?)
                OR (subcategories IN ('[]', '') AND subcategory = ?)
              )
            """.trimIndent(),
            listOf(move.to, move.from, jsonArray(listOf(move.type)), move.type, move.type),
        )
    }
}
