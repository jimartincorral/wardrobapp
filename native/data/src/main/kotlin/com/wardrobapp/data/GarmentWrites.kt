package com.wardrobapp.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Writing garments.
 *
 * Photo references are reduced to bare filenames here, at the write boundary, so
 * callers can keep passing the full URIs that image pickers hand them. Storing
 * the directory is what made a restored wardrobe show broken images: the
 * absolute path is not stable across installs.
 *
 * Deleting is transactional, which the TypeScript's is not.
 */
class GarmentWrites(private val driver: SqlDriver) {

    /** Everything needed to store a new garment. */
    data class NewGarment(
        val id: String,
        val imageUri: String,
        val imageUriNoBg: String? = null,
        val imageUris: List<String> = emptyList(),
        val imageUrisNoBg: List<String> = emptyList(),
        val category: String,
        val subcategories: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val brand: String? = null,
        val colorPrimary: String,
        val colorSecondary: String? = null,
        val colorPalette: List<String> = emptyList(),
        val size: String? = null,
        val purchaseDate: String? = null,
        /** Supplied rather than read from a clock, so a write is reproducible. */
        val now: String,
    )

    fun insert(garment: NewGarment) {
        // The single-value column mirrors the first of the list, exactly as the
        // TypeScript does: both are read, and older builds only wrote the former.
        val subcategory = garment.subcategories.firstOrNull()

        driver.execute(
            """
            INSERT INTO garments (
                id, image_uri, image_uri_nobg, image_uris, image_uris_nobg, category,
                subcategory, subcategories, tags, brand, color_primary, color_secondary,
                color_palette, size, purchase_date, is_available, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
            """.trimIndent(),
            listOf(
                garment.id,
                toStoredImageRef(garment.imageUri),
                garment.imageUriNoBg?.let { toStoredImageRef(it) },
                jsonArray(garment.imageUris.map { toStoredImageRef(it) }),
                jsonArray(garment.imageUrisNoBg.map { toStoredImageRef(it) }),
                garment.category,
                subcategory,
                jsonArray(garment.subcategories),
                jsonArray(garment.tags),
                garment.brand,
                garment.colorPrimary,
                garment.colorSecondary,
                jsonArray(garment.colorPalette),
                garment.size,
                garment.purchaseDate,
                garment.now,
                garment.now,
            ),
        )
    }

    /** Fields a garment edit may change. Absent means "leave alone". */
    data class GarmentEdit(
        val imageUri: String? = null,
        val imageUriNoBg: String? = null,
        val imageUris: List<String>? = null,
        val imageUrisNoBg: List<String>? = null,
        val category: String? = null,
        val subcategories: List<String>? = null,
        val tags: List<String>? = null,
        val brand: String? = null,
        val colorPrimary: String? = null,
        val colorSecondary: String? = null,
        val colorPalette: List<String>? = null,
        val size: String? = null,
        val purchaseDate: String? = null,
    )

    /**
     * Apply an edit. Returns false when there was nothing to change, so a caller
     * can tell "no-op" from "wrote".
     */
    fun update(id: String, edit: GarmentEdit, now: String): Boolean {
        val fields = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        fun set(column: String, value: Any?) {
            fields.add("$column = ?")
            args.add(value)
        }

        edit.imageUri?.let { set("image_uri", toStoredImageRef(it)) }
        edit.imageUriNoBg?.let { set("image_uri_nobg", toStoredImageRef(it)) }
        edit.imageUris?.let { set("image_uris", jsonArray(it.map(::toStoredImageRef))) }
        edit.imageUrisNoBg?.let { set("image_uris_nobg", jsonArray(it.map(::toStoredImageRef))) }
        edit.category?.let { set("category", it) }
        edit.tags?.let { set("tags", jsonArray(it)) }
        edit.brand?.let { set("brand", it) }
        edit.colorPrimary?.let { set("color_primary", it) }
        edit.colorSecondary?.let { set("color_secondary", it) }
        edit.colorPalette?.let { set("color_palette", jsonArray(it)) }
        edit.size?.let { set("size", it) }
        edit.purchaseDate?.let { set("purchase_date", it) }

        edit.subcategories?.let {
            set("subcategories", jsonArray(it))
            // Kept in step with the list, since both are read.
            set("subcategory", it.firstOrNull())
        }

        if (fields.isEmpty()) return false

        set("updated_at", now)
        args.add(id)

        driver.execute("UPDATE garments SET ${fields.joinToString(", ")} WHERE id = ?", args)
        return true
    }

    fun markUnavailable(id: String, now: String) {
        driver.execute(
            "UPDATE garments SET is_available = 0, unavailable_date = ?, updated_at = ? WHERE id = ?",
            listOf(now, now, id),
        )
    }

    fun markAvailable(id: String, now: String) {
        driver.execute(
            "UPDATE garments SET is_available = 1, unavailable_date = NULL, updated_at = ? WHERE id = ?",
            listOf(now, id),
        )
    }

    /**
     * Delete a garment and everything that only existed because of it.
     *
     * Atomic, unlike the TypeScript: it issues these as separate statements, so a
     * failure partway through leaves the row gone but its learned pair scores
     * behind, or an outfit pointing at a garment that no longer exists.
     *
     * Returns the stored photo references, since deleting the files themselves is
     * the caller's job -- this module does not touch the filesystem.
     */
    fun delete(id: String): List<String> = driver.transaction {
        val photos = driver.query(
            "SELECT image_uri, image_uri_nobg, image_uris, image_uris_nobg FROM garments WHERE id = ?",
            listOf(id),
        ).firstOrNull()?.let { row ->
            buildList {
                addAll(parseStringArray(row["image_uris"]))
                addAll(parseStringArray(row["image_uris_nobg"]))
                (row["image_uri"] as? String)?.let { add(it) }
                (row["image_uri_nobg"] as? String)?.let { add(it) }
            }.filter { it.isNotEmpty() }.distinct()
        } ?: emptyList()

        driver.execute("DELETE FROM garments WHERE id = ?", listOf(id))
        driver.execute(
            "DELETE FROM garment_pair_scores WHERE garment_id_a = ? OR garment_id_b = ?",
            listOf(id, id),
        )
        OutfitWrites(driver).removeGarment(id)

        photos
    }
}

/**
 * Encode a list the way the schema stores it.
 *
 * Through the JSON library rather than string concatenation: escaping is exactly
 * the kind of thing that looks fine until a garment is tagged with a quote mark.
 */
internal fun jsonArray(values: List<String>): String =
    JsonArray(values.map { JsonPrimitive(it) }).toString()
