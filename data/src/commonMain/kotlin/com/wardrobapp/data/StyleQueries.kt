package com.wardrobapp.data

import kotlinx.serialization.Serializable

/**
 * What the style model has said about this wardrobe: a vector per garment
 * photo, and the photos of looks the reader likes with theirs.
 *
 * Two tables the Home Assistant app fills -- it has the model, see the
 * server's StyleEncoder -- and the phone receives through sync (in time; a
 * phone that does not sync has them empty, which the engine reads as no
 * opinion). Both are in the shared schema so the same queries serve both
 * sides, and so a backup carries them.
 *
 * A garment's vector is keyed by the photo it was computed from, so a garment
 * whose photo changed is embedded again and one whose photo did not is not:
 * embedding is seconds on a Pi, and a wardrobe should not be re-embedded
 * because a tag was edited.
 *
 * Inspirations are deleted by tombstone (`deleted_at`) rather than by row,
 * because one day they will sync, and a deletion has to be a fact one side
 * can tell the other. They are not in the `deletions` table: its kind is an
 * enum older phones read off the wire, and a kind they do not know would
 * fail their whole sync.
 */
class StyleQueries(private val driver: SqlDriver) {

    /** Every garment's vector, by id, as the engine's lookup wants it. */
    fun embeddings(): Map<String, FloatArray> = driver
        .query("SELECT garment_id, vector FROM garment_embeddings")
        .associate { row -> jsString(row["garment_id"]) to unpackVector(row["vector"]) }

    /** The photo each embedded garment's vector was computed from, by garment id. */
    fun embeddedPhotos(): Map<String, String> = driver
        .query("SELECT garment_id, photo FROM garment_embeddings")
        .associate { row -> jsString(row["garment_id"]) to jsString(row["photo"]) }

    fun putEmbedding(garmentId: String, photo: String, model: String, vector: FloatArray, now: String) {
        driver.execute(
            "INSERT OR REPLACE INTO garment_embeddings (garment_id, photo, model, vector, computed_at) VALUES (?, ?, ?, ?, ?)",
            listOf(garmentId, photo, model, packVector(vector), now),
        )
    }

    fun deleteEmbedding(garmentId: String) {
        driver.execute("DELETE FROM garment_embeddings WHERE garment_id = ?", listOf(garmentId))
    }

    /** The looks the reader likes, newest first, the deleted ones left out. */
    fun inspirations(): List<InspirationRecord> = driver
        .query("SELECT id, image_uri, created_at FROM inspirations WHERE deleted_at IS NULL ORDER BY created_at DESC, id")
        .map { row ->
            InspirationRecord(
                id = jsString(row["id"]),
                imageUri = jsString(row["image_uri"]),
                createdAt = jsString(row["created_at"]),
            )
        }

    fun inspiration(id: String): InspirationRecord? = inspirations().firstOrNull { it.id == id }

    fun addInspiration(id: String, imageUri: String, now: String) {
        driver.execute(
            "INSERT INTO inspirations (id, image_uri, created_at, updated_at) VALUES (?, ?, ?, ?)",
            listOf(id, imageUri, now, now),
        )
    }

    /** Mark an inspiration deleted; the photo it refers to, for the caller to delete, or null if it was not there. */
    fun deleteInspiration(id: String, now: String): String? {
        val photo = driver
            .query("SELECT image_uri FROM inspirations WHERE id = ? AND deleted_at IS NULL", listOf(id))
            .firstOrNull()
            ?.let { jsString(it["image_uri"]) }
            ?: return null
        driver.execute("UPDATE inspirations SET deleted_at = ?, updated_at = ?, vector = NULL WHERE id = ?", listOf(now, now, id))
        return photo
    }

    /** The looks that have no vector yet. */
    fun inspirationsToEmbed(): List<InspirationRecord> = driver
        .query("SELECT id, image_uri, created_at FROM inspirations WHERE deleted_at IS NULL AND vector IS NULL ORDER BY created_at")
        .map { row ->
            InspirationRecord(
                id = jsString(row["id"]),
                imageUri = jsString(row["image_uri"]),
                createdAt = jsString(row["created_at"]),
            )
        }

    fun putInspirationVector(id: String, model: String, vector: FloatArray) {
        driver.execute("UPDATE inspirations SET model = ?, vector = ? WHERE id = ?", listOf(model, packVector(vector), id))
    }

    /** Every look's vector, for the taste; looks not yet embedded are not here. */
    fun inspirationVectors(): List<FloatArray> = driver
        .query("SELECT vector FROM inspirations WHERE deleted_at IS NULL AND vector IS NOT NULL")
        .map { unpackVector(it["vector"]) }

    companion object {
        /**
         * A vector as the BLOB column holds it: little-endian 32-bit floats,
         * nothing else. Written by hand rather than through a buffer class
         * so the same bytes mean the same vector on every platform the
         * database is read on.
         */
        fun packVector(vector: FloatArray): ByteArray {
            val bytes = ByteArray(vector.size * 4)
            for ((i, value) in vector.withIndex()) {
                val bits = value.toRawBits()
                bytes[i * 4] = (bits and 0xFF).toByte()
                bytes[i * 4 + 1] = (bits shr 8 and 0xFF).toByte()
                bytes[i * 4 + 2] = (bits shr 16 and 0xFF).toByte()
                bytes[i * 4 + 3] = (bits shr 24 and 0xFF).toByte()
            }
            return bytes
        }

        fun unpackVector(value: Any?): FloatArray {
            val bytes = value as? ByteArray ?: return FloatArray(0)
            return FloatArray(bytes.size / 4) { i ->
                val bits = (bytes[i * 4].toInt() and 0xFF) or
                    (bytes[i * 4 + 1].toInt() and 0xFF shl 8) or
                    (bytes[i * 4 + 2].toInt() and 0xFF shl 16) or
                    (bytes[i * 4 + 3].toInt() and 0xFF shl 24)
                Float.fromBits(bits)
            }
        }
    }
}

/** A photo of a look the reader likes, as the screen lists it and the browser is sent it. */
@Serializable
data class InspirationRecord(
    val id: String,
    /** A reference the screen can draw, resolved the way a garment's photo is. */
    val imageUri: String,
    val createdAt: String,
)
