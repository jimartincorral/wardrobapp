package com.wardrobapp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Garments saved under a category their type has since left, brought along:
 * on open, after a restore, and as they arrive in a sync. Against both schemas
 * that exist on phones, as the other write paths are.
 */
class GarmentMovesTest {

    private val t1 = "2025-03-01T10:00:00.000Z"

    /** What isoTimestamp writes, and so what sync compares as text. */
    private val isoTimestamp = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")

    /** A garment row written directly, so a test can write the shapes older builds left behind. */
    private fun SqlDriver.insertRaw(id: String, category: String, subcategory: String?, subcategories: String) {
        execute(
            """
            INSERT INTO garments (
                id, image_uri, image_uris, image_uris_nobg, category, subcategory, subcategories,
                tags, color_primary, color_palette, is_available, created_at, updated_at
            ) VALUES (?, ?, ?, '[]', ?, ?, ?, '[]', '#808080', '["#808080"]', 1, ?, ?)
            """.trimIndent(),
            listOf(id, "$id.jpg", """["$id.jpg"]""", category, subcategory, subcategories, t1, t1),
        )
    }

    private fun SqlDriver.categoryOf(id: String): String =
        query("SELECT category FROM garments WHERE id = ?", listOf(id)).single()["category"] as String

    private fun SqlDriver.updatedAt(id: String): String =
        query("SELECT updated_at FROM garments WHERE id = ?", listOf(id)).single()["updated_at"] as String

    /** Every shape a cardigan filed as outerwear can be stored in, and the rows that must be left alone. */
    private fun SqlDriver.seed() {
        insertRaw("json", "outerwear", "Cardigan", """["Cardigan"]""")
        insertRaw("comma", "outerwear", "Cardigan", "Cardigan")
        insertRaw("singular", "outerwear", "Cardigan", "[]")
        insertRaw("mixed", "outerwear", "Cardigan", """["Cardigan","Jacket"]""")
        insertRaw("coat", "outerwear", "Coat", """["Coat"]""")
        insertRaw("already", "midlayer", "Cardigan", """["Cardigan"]""")
        insertRaw("top", "tops", "Sweater", """["Sweater"]""")
    }

    @Test
    fun `a cardigan saved as outerwear is a mid-layer from the next open`() {
        for ((schema, open) in JdbcSqlDriver.bothSchemas()) {
            val driver = open()
            driver.seed()

            // The next open, which is what applies the schema.
            WardrobeSchema.applyTo(driver)

            for (id in listOf("json", "comma", "singular")) {
                assertEquals("midlayer", driver.categoryOf(id), "$schema: $id")
                assertTrue(isoTimestamp.matches(driver.updatedAt(id)), "$schema: $id is stamped as every row is")
                assertNotEquals(t1, driver.updatedAt(id), "$schema: $id is stamped as changed, so a sync carries it")
            }
            // A cardigan that is also a jacket is not wholly either, and stays
            // for somebody to choose.
            assertEquals("outerwear", driver.categoryOf("mixed"), schema)
            assertEquals("outerwear", driver.categoryOf("coat"), schema)
            for (id in listOf("mixed", "coat", "already", "top")) {
                assertEquals(t1, driver.updatedAt(id), "$schema: $id was not touched")
            }
        }
    }

    @Test
    fun `moving twice moves nothing the second time`() {
        for ((schema, open) in JdbcSqlDriver.bothSchemas()) {
            val driver = open()
            driver.seed()
            assertEquals(3, GarmentMoves.applyTo(driver), schema)
            val stamped = driver.updatedAt("json")

            assertEquals(0, GarmentMoves.applyTo(driver), "$schema: nothing left to move")
            assertEquals(stamped, driver.updatedAt("json"), "$schema: and nothing stamped again")
        }
    }

    @Test
    fun `a cardigan arriving from a device that has not moved it is moved as it lands, on both sides`() {
        for ((schema, open) in JdbcSqlDriver.bothSchemas()) {
            val server = open()
            val phone = open()
            // The phone has not been updated: it filed a new cardigan the old way.
            phone.insertRaw("new", "outerwear", "Cardigan", """["Cardigan"]""")

            val phoneStore = SyncStore(phone)
            val serverStore = SyncStore(server)
            val phoneSnapshot = phoneStore.snapshot()
            val serverSnapshot = serverStore.snapshot()
            val merged = merge(phoneSnapshot, serverSnapshot)
            serverStore.apply(changesTo(serverSnapshot, merged))

            assertEquals("midlayer", server.categoryOf("new"), "$schema: moved as it landed on the server")
            assertNotEquals(t1, server.updatedAt("new"), schema)

            // And newer than the phone's copy, so the next sync brings the move
            // back to the phone by the ordinary rule.
            val again = merge(phoneStore.snapshot(), serverStore.snapshot())
            assertEquals("midlayer", again.garments.single().category, schema)
        }
    }
}
