package com.wardrobapp.presentation

import java.text.DateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class StoredDatesTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val madrid = TimeZone.getTimeZone("Europe/Madrid")
    private val losAngeles = TimeZone.getTimeZone("America/Los_Angeles")
    private val english = Locale.forLanguageTag("en-US")
    private val spanish = Locale.forLanguageTag("es-ES")

    private fun format(value: String, zone: TimeZone = utc, locale: Locale = english) =
        formatStoredDate(value, zone, locale)

    @Test
    fun `formats the date the picker stores`() {
        assertEquals("Jan 15, 2026", format("2026-01-15"))
    }

    @Test
    fun `formats a full timestamp`() {
        assertEquals("Apr 2, 2026", format("2026-04-02T10:11:12.000Z"))
    }

    @Test
    fun `reads a timestamp in the reader's own timezone`() {
        // 23:30 UTC is already the next day in Madrid and still the previous
        // afternoon in Los Angeles. Showing the UTC date on a phone in Madrid
        // would be a day out, which is why the zone is an argument.
        val lateEvening = "2026-04-02T23:30:00.000Z"

        assertEquals("Apr 2, 2026", format(lateEvening, zone = utc))
        assertEquals("Apr 3, 2026", format(lateEvening, zone = madrid))
        assertEquals("Apr 2, 2026", format(lateEvening, zone = losAngeles))
    }

    @Test
    fun `uses the device's language rather than the app's`() {
        // The whole reason this is not a port of date-fns's `MMM d, yyyy`: a
        // phone set to Spanish should not be shown "Jan".
        assertEquals("2 abr 2026", format("2026-04-02", locale = spanish))
    }

    @Test
    fun `refuses a value that merely starts with a date`() {
        // Accepting a partial match would turn anything beginning with a date
        // into that date, quietly throwing the rest away -- so a field holding
        // two dates, or a date with a note after it, would render as a
        // confident-looking wrong answer instead of as itself.
        assertEquals("2026-01-15 and 2026-02-20", format("2026-01-15 and 2026-02-20"))
        assertEquals("2026-04-02T23:30:00.000Z (imported)", format("2026-04-02T23:30:00.000Z (imported)"))
    }

    @Test
    fun `honours an offset in the timestamp rather than assuming Z`() {
        // 23:30+02:00 is 21:30 UTC, so in Madrid it is still 23:30 on the 2nd --
        // whereas 23:30Z would already be the 3rd there. Reading the offset is
        // what tells those apart.
        assertEquals("Apr 2, 2026", format("2026-04-02T23:30:00.000+02:00", zone = madrid))
        assertEquals("Apr 3, 2026", format("2026-04-02T23:30:00.000Z", zone = madrid))
    }

    @Test
    fun `handles a timestamp with no zone at all`() {
        // Read as local time, which is the only reading available.
        assertEquals("Apr 2, 2026", format("2026-04-02T23:30:00", zone = madrid))
    }

    @Test
    fun `gives back anything it cannot read rather than hiding it`() {
        assertEquals("not a date", format("not a date"))
        assertEquals("2026-13-45", format("2026-13-45"))
        assertEquals("", format(""))
    }

    @Test
    fun `honours the requested style`() {
        assertEquals(
            "January 15, 2026",
            formatStoredDate("2026-01-15", utc, english, DateFormat.LONG),
        )
    }
}
