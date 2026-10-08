package com.wardrobapp.data

/*
 * Undoing a delete.
 *
 * Deleting a garment or an outfit used to be the one thing in the app with no
 * way back short of a restore. The confirmation dialog stood in for one, and
 * a dialog is a poor undo: it asks before the mistake is visible, and once
 * somebody has tapped through it the answer is final.
 *
 * The shape chosen here is "delete now, bring back on request", not "delete
 * later". Deleting later -- holding the row for a few seconds and hiding it
 * meanwhile -- would mean every list in the app, on both platforms, filtering
 * by a set of ids that are gone-but-not-yet, and the sync and the backup
 * asking the same question. Deleting now means every screen sees an ordinary
 * delete, and an undo is an ordinary re-insert of exactly what went.
 *
 * So a delete captures what it removes -- the row, the learned scores keyed on
 * it, the outfits it was taken out of and the ones deleted for being left
 * empty, their ratings -- as the raw rows, and keeps them in memory for a
 * while under the id. A restore writes them back with a fresh timestamp. The
 * timestamp is what makes this survive a sync that happened in between: the
 * delete was recorded for the other side as of its moment, and a row changed
 * after that moment wins over the deletion by the ordinary merge rule, so the
 * garment comes back everywhere rather than being deleted again by the next
 * sync. The deletion record itself is dropped here too.
 *
 * What is captured is raw rows rather than records, on purpose: a record is
 * what a screen needs, and leaves out what a screen does not -- the created
 * time, the primary photo's cut-out, a column added since. A row put back
 * column for column is the row that was there.
 *
 * Photos are not deleted with the row any more; they are deleted when the
 * window closes without an undo, by whoever holds the files (the sources, see
 * DatabaseGarmentDetailSource). A photo that sat on disk a few seconds longer
 * costs nothing; one deleted with the row would have made the undo a garment
 * with no pictures.
 */

/** The rows a garment's delete removed, enough to put every one of them back. */
class DeletedGarment internal constructor(
    val id: String,
    /** The photo files the garment referred to, for deleting once the undo window has closed. */
    val photos: List<String>,
    internal val garment: Row,
    internal val pairScores: List<Row>,
    internal val scores: List<Row>,
    /** What happened to each outfit that held the garment. */
    internal val outfits: List<OutfitChange>,
)

/** The rows an outfit's delete removed. */
class DeletedOutfit internal constructor(
    val id: String,
    internal val outfit: Row,
    internal val ratings: List<Row>,
)

/** What deleting a garment did to one outfit that held it. */
internal sealed interface OutfitChange {
    /** It lost the garment and kept the rest; these are its ids and update time from before. */
    data class Shrunk(val id: String, val garmentIds: String, val updatedAt: Any?) : OutfitChange

    /** It had nothing else and was deleted with the garment. */
    data class Gone(val outfit: DeletedOutfit) : OutfitChange
}

internal typealias Row = Map<String, Any?>

/** One row of [table] with [id], as the driver reads it; null for no such row. */
internal fun SqlDriver.row(table: String, id: String): Row? =
    query("SELECT * FROM $table WHERE id = ?", listOf(id)).firstOrNull()

/**
 * Write [row] back into [table], every column as it was.
 *
 * `INSERT OR REPLACE` rather than a plain insert, so a row that somehow came
 * back on its own in the meantime -- a sync bringing another phone's copy --
 * is overwritten with this one instead of failing the undo; and rather than
 * an update, so a row that did not come back is inserted. For a table with
 * dependants that cascade on delete, the dependants are written after, which
 * every caller here does.
 */
internal fun SqlDriver.putRow(table: String, row: Row, vararg overriding: Pair<String, Any?>) {
    val values = LinkedHashMap(row).apply { for ((column, value) in overriding) put(column, value) }
    val columns = values.keys.joinToString(", ")
    val marks = values.keys.joinToString(", ") { "?" }
    execute("INSERT OR REPLACE INTO $table ($columns) VALUES ($marks)", values.values.toList())
}
