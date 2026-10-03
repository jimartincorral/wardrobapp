package com.wardrobapp.data

import java.util.Locale
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The timestamp format written into the rows.
 *
 * Worth pinning exactly: the same database is read by the React Native app, and
 * these columns are sorted as text. A row written in a different shape would
 * sort into the wrong place rather than failing visibly -- the wardrobe list
 * would simply be in the wrong order, which is the sort of bug nobody reports.
 */
class TimestampsTest {

    private val originalZone = TimeZone.getDefault()
    private val originalLocale = Locale.getDefault()

    @AfterTest
    fun restore() {
        TimeZone.setDefault(originalZone)
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `matches what Date toISOString produces`() {
        // 2026-08-23T14:15:16.017Z, as milliseconds.
        assertEquals("2026-08-23T14:15:16.017Z", isoTimestamp(1787494516017L))
        assertEquals("1970-01-01T00:00:00.000Z", isoTimestamp(0))
    }

    @Test
    fun `pads the milliseconds`() {
        // Three digits always: "…:00.7Z" would sort before "…:00.60Z".
        assertEquals("1970-01-01T00:00:00.007Z", isoTimestamp(7))
        assertEquals("1970-01-01T00:00:00.070Z", isoTimestamp(70))
    }

    @Test
    fun `writes UTC whatever the device is set to`() {
        val instant = 1787494516017L

        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        val tokyo = isoTimestamp(instant)
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        val losAngeles = isoTimestamp(instant)

        assertEquals("2026-08-23T14:15:16.017Z", tokyo)
        assertEquals(tokyo, losAngeles)
    }

    @Test
    fun `writes digits whatever the device's language is`() {
        // A locale with its own numerals would otherwise produce a timestamp
        // SQLite sorts as text and neither app can parse.
        Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))

        assertEquals("2026-08-23T14:15:16.017Z", isoTimestamp(1787494516017L))
    }

    @Test
    fun `reads back exactly what it wrote`() {
        val instants = listOf(0L, 7L, 1000L, 1787494516017L, 4102444800000L)

        for (instant in instants) {
            assertEquals(instant, epochMillisOfIso(isoTimestamp(instant)))
        }
    }

    @Test
    fun `reads the shape Drive stamps a file with`() {
        // RFC 3339 without the fractional seconds, which is what Drive returns
        // when they are zero. A parser that only knew this app's own shape would
        // drop those files out of the backup list entirely.
        assertEquals(1787494516000L, epochMillisOfIso("2026-08-23T14:15:16Z"))
    }

    @Test
    fun `reads UTC whatever the device is set to`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        val tokyo = epochMillisOfIso("2026-08-23T14:15:16.017Z")
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        val losAngeles = epochMillisOfIso("2026-08-23T14:15:16.017Z")

        assertEquals(1787494516017L, tokyo)
        assertEquals(tokyo, losAngeles)
    }

    @Test
    fun `refuses what it cannot read rather than guessing`() {
        // Null drops one record. A guess sorts it into the wrong place, and the
        // oldest backup is the one that gets deleted.
        assertEquals(null, epochMillisOfIso(""))
        assertEquals(null, epochMillisOfIso("not a date"))
        assertEquals(null, epochMillisOfIso("2026-08-23"))
        assertEquals(null, epochMillisOfIso("2026-13-01T00:00:00.000Z"))
        assertEquals(null, epochMillisOfIso("1787494516017"))
    }

    @Test
    fun `sorts as text in the same order as in time`() {
        // Which is the whole reason the shape has to be fixed: every read path
        // orders by these columns as strings.
        val instants = listOf(0L, 7L, 1000L, 1787494516017L, 1787494516018L, 4102444800000L)
        val written = instants.map(::isoTimestamp)

        assertEquals(written.sorted(), written)
        assertTrue(written.toSet().size == written.size, "two instants wrote the same text")
    }
}
