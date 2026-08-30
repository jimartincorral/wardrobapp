package com.wardrobapp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a backup carries the way the app is set up.
 *
 * The types are the whole reason this is not a plain map of strings.
 * SharedPreferences is typed, and reading a value back as the wrong type throws
 * where it is read -- a column count written as a Long and fetched with `getInt`
 * is a crash when somebody opens their wardrobe, a long way from the restore that
 * caused it.
 *
 * The other half is that **none of the reading may throw.** An archive is a file
 * that has been uploaded, downloaded and copied about, and its settings are the
 * least important thing in it. Losing a wardrobe because a theme was recorded
 * oddly would be the wrong trade by a wide margin.
 */
class ArchiveSettingsTest {

    private val everything = ArchiveSettings(
        language = "es",
        preferences = mapOf(
            "appearance" to mapOf(
                "theme_mode" to SettingValue.Text("dark"),
                "wardrobe_columns" to SettingValue.Whole(3),
            ),
            "schedule" to mapOf(
                "enabled" to SettingValue.Flag(true),
                "last_run_at" to SettingValue.Big(1_788_000_000_000L),
            ),
        ),
    )

    @Test
    fun `everything survives the round trip with the type it went in as`() {
        assertEquals(everything, readArchiveSettings(writeArchiveSettings(everything)))
    }

    @Test
    fun `an int stays an int and a long stays a long`() {
        // Not covered by the round trip above on its own: both are numbers in
        // JSON, and inferring the type from the value would read 3 back as a Long
        // and throw at whichever screen asks for it as an Int.
        val read = readArchiveSettings(writeArchiveSettings(everything))!!

        assertTrue(read.preferences.getValue("appearance")["wardrobe_columns"] is SettingValue.Whole)
        assertTrue(read.preferences.getValue("schedule")["last_run_at"] is SettingValue.Big)
    }

    @Test
    fun `following the system is not the same as having no language`() {
        val noLanguage = ArchiveSettings(preferences = mapOf("appearance" to emptyMap()))

        assertNull(readArchiveSettings(writeArchiveSettings(noLanguage))?.language)
    }

    @Test
    fun `nothing recorded reads as nothing to apply`() {
        assertTrue(ArchiveSettings().isEmpty)
        assertTrue(ArchiveSettings(preferences = mapOf("appearance" to emptyMap())).isEmpty)
        assertTrue(!everything.isEmpty)
    }

    @Test
    fun `a file that is not settings at all is ignored rather than thrown`() {
        assertNull(readArchiveSettings("this is not json"))
        assertNull(readArchiveSettings(""))
        assertEquals(ArchiveSettings(), readArchiveSettings("[1, 2, 3]") ?: ArchiveSettings())
    }

    @Test
    fun `one unreadable entry does not take the rest of the file with it`() {
        // Written by a later build that had a type this one does not know, or by
        // something that got it wrong. The theme still comes back.
        val text = """
            {
              "language": "es",
              "preferences": {
                "appearance": {
                  "theme_mode": { "type": "string", "value": "dark" },
                  "future_thing": { "type": "colour", "value": "puce" },
                  "malformed": "not an object"
                }
              }
            }
        """.trimIndent()

        val read = readArchiveSettings(text)!!

        assertEquals("es", read.language)
        assertEquals(
            mapOf("theme_mode" to SettingValue.Text("dark")),
            read.preferences.getValue("appearance"),
        )
    }

    @Test
    fun `a number too large for an int is dropped rather than wrapped`() {
        // Wrapping would produce a plausible-looking wrong value -- a column count
        // of something absurd -- where dropping leaves the default in place.
        val text = """
            {"preferences": {"appearance": {"columns": {"type": "int", "value": 99999999999}}}}
        """.trimIndent()

        assertEquals(emptyMap(), readArchiveSettings(text)!!.preferences.getValue("appearance"))
    }

    @Test
    fun `a language that is not a string is not a language`() {
        assertNull(readArchiveSettings("""{"language": 42}""")?.language)
    }
}
