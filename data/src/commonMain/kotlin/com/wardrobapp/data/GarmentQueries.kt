package com.wardrobapp.data

/**
 * Reading garments.
 *
 * The SQL is deliberately the same shape the TypeScript issues, so the two read
 * the same rows in the same order from the same database. Ordering matters more
 * than it looks: `created_at DESC` is what the suggestion engine's candidate
 * lists inherit, and it was the source of the tie-breaking bias the algorithm
 * fixes had to correct.
 */
class GarmentQueries(
    private val driver: SqlDriver,
    private val imageDirectory: String,
) {
    /** Filters the wardrobe list supports. */
    data class Filters(
        val category: String? = null,
        /** Null means "available only", matching the TypeScript's default. */
        val availableOnly: Boolean? = null,
        val search: String? = null,
    )

    fun allGarments(filters: Filters = Filters()): List<GarmentRecord> {
        val sql = StringBuilder("SELECT * FROM garments WHERE 1=1")
        val args = mutableListOf<Any?>()

        filters.category?.let {
            sql.append(" AND category = ?")
            args.add(it)
        }

        // `!= false` rather than `== true`: absent means available-only, which is
        // what the TypeScript's `available_only !== false` does.
        if (filters.availableOnly != false) {
            sql.append(" AND is_available = 1")
        }

        filters.search?.let { term ->
            // Category included, so that searching the wardrobe for "shoes"
            // finds the shoes. It also keeps this in step with
            // `garmentMatchesSearch`, which the outfit picker uses over garments
            // it already holds -- the same question asked two ways, and the field
            // lists have to agree or one of them finds what the other cannot.
            sql.append(
                " AND (brand LIKE ? OR tags LIKE ? OR subcategory LIKE ? " +
                    "OR subcategories LIKE ? OR size LIKE ? OR category LIKE ?)"
            )
            val like = "%$term%"
            repeat(6) { args.add(like) }
        }

        sql.append(" ORDER BY created_at DESC")

        return driver.query(sql.toString(), args).map { normalizeGarmentRow(it, imageDirectory) }
    }

    fun garment(id: String): GarmentRecord? =
        driver.query("SELECT * FROM garments WHERE id = ?", listOf(id))
            .firstOrNull()
            ?.let { normalizeGarmentRow(it, imageDirectory) }

    fun availableCount(): Long = count("SELECT COUNT(*) as count FROM garments WHERE is_available = 1")

    fun unavailableCount(): Long = count("SELECT COUNT(*) as count FROM garments WHERE is_available = 0")

    private fun count(sql: String): Long =
        (driver.query(sql).firstOrNull()?.get("count") as? Number)?.toLong() ?: 0L

    /** Distinct brands, case-insensitively sorted, blanks excluded. */
    fun brands(): List<String> = driver.query(
        """
        SELECT DISTINCT TRIM(brand) as brand
        FROM garments
        WHERE brand IS NOT NULL AND TRIM(brand) != ''
        ORDER BY brand COLLATE NOCASE ASC
        """.trimIndent()
        // No blank filter here: the query already excludes them, and having the
        // guard in both places meant neither was load-bearing -- removing either
        // alone left the behaviour intact, so no test could pin either.
    ).mapNotNull { it["brand"] as? String }

    /**
     * Every tag in use, first spelling kept, sorted case-insensitively.
     *
     * Derived from the rows rather than a query because tags live in a JSON
     * column, which is also why the TypeScript reads every garment to do this.
     */
    fun tags(): List<String> {
        val seen = mutableSetOf<String>()
        val tags = mutableListOf<String>()

        for (garment in allGarments(Filters(availableOnly = false))) {
            for (tag in garment.tags) {
                val trimmed = tag.trim()
                if (trimmed.isEmpty() || !seen.add(trimmed.lowercase())) continue
                tags.add(trimmed)
            }
        }

        return tags.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
}
