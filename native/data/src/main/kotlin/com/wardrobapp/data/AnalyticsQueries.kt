package com.wardrobapp.data

/**
 * Wardrobe breakdowns.
 *
 * Mostly aggregation, which belongs in SQL. The one exception is the
 * subcategory split, because subcategories live in a JSON column with a
 * single-value fallback -- so the grouping happens here, over parsed rows.
 */
class AnalyticsQueries(private val driver: SqlDriver) {

    /** A label and how many available garments carry it. */
    data class Count(val label: String, val count: Long)

    /** No subcategory recorded. The caller decides how to word it. */
    companion object {
        const val NO_SUBCATEGORY = "__none__"
    }

    fun byCategory(): List<Count> = driver.query(
        """
        SELECT category, COUNT(*) as count
        FROM garments
        WHERE is_available = 1
        GROUP BY category
        ORDER BY count DESC
        """.trimIndent()
    ).map { Count(jsString(it["category"] ?: ""), (it["count"] as Number).toLong()) }

    /**
     * Grouped case-insensitively, because the row normalizer dedupes palettes
     * that way: '#abcdef' and '#ABCDEF' are one colour everywhere else in the
     * app, and grouping on the raw column reported them as two.
     */
    fun byColor(): List<Count> = driver.query(
        """
        SELECT UPPER(color_primary) as color, COUNT(*) as count
        FROM garments
        WHERE is_available = 1 AND color_primary IS NOT NULL AND color_primary != ''
        GROUP BY UPPER(color_primary)
        ORDER BY count DESC
        """.trimIndent()
    ).map { Count(jsString(it["color"] ?: ""), (it["count"] as Number).toLong()) }

    /**
     * Grouped on the trimmed value, matching how the brand picker lists brands.
     * Grouping on the raw column listed 'Uniqlo', ' Uniqlo' and 'Uniqlo ' as
     * three brands with one garment each.
     */
    fun byBrand(): List<Count> = driver.query(
        """
        SELECT TRIM(brand) as brand, COUNT(*) as count
        FROM garments
        WHERE is_available = 1 AND brand IS NOT NULL AND TRIM(brand) != ''
        GROUP BY TRIM(brand)
        ORDER BY count DESC
        """.trimIndent()
    ).map { Count(jsString(it["brand"] ?: ""), (it["count"] as Number).toLong()) }

    /** Subcategory counts within each category, most common first. */
    fun bySubcategory(): Map<String, List<Count>> {
        val rows = driver.query(
            "SELECT category, subcategory, subcategories FROM garments WHERE is_available = 1"
        )

        val byCategory = linkedMapOf<String, LinkedHashMap<String, Long>>()

        for (row in rows) {
            val category = row["category"] as? String ?: continue
            if (category.isEmpty()) continue

            val parsed = parseStringArray(row["subcategories"])
            val subs = when {
                parsed.isNotEmpty() -> parsed
                (row["subcategory"] as? String)?.isNotEmpty() == true -> listOf(row["subcategory"] as String)
                else -> listOf(NO_SUBCATEGORY)
            }

            val counts = byCategory.getOrPut(category) { linkedMapOf() }
            for (sub in subs) {
                counts[sub] = (counts[sub] ?: 0L) + 1L
            }
        }

        return byCategory.mapValues { (_, counts) ->
            counts.map { (sub, count) -> Count(sub, count) }.sortedByDescending { it.count }
        }
    }

    /** How long a retired garment was owned, longest first. */
    data class Lifespan(val garment: GarmentRecord, val days: Long)

    fun lifespans(imageDirectory: String): List<Lifespan> = driver.query(
        """
        SELECT g.*,
               CAST(julianday(g.unavailable_date) - julianday(g.purchase_date) AS INTEGER) as lifespan_days
        FROM garments g
        WHERE g.is_available = 0
          AND g.unavailable_date IS NOT NULL
          AND g.purchase_date IS NOT NULL
        ORDER BY lifespan_days DESC
        """.trimIndent()
    ).map { row ->
        Lifespan(
            garment = normalizeGarmentRow(row, imageDirectory),
            days = (row["lifespan_days"] as Number).toLong(),
        )
    }
}
