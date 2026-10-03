package com.wardrobapp.presentation

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The common parse of a stored date, against what it replaced.
 *
 * StoredDatesTest pins how dates are shown; this pins which strings are dates,
 * now that the answer is common code shared with the browser rather than
 * SimpleDateFormat. The old parse is kept here, verbatim, as the reference: on
 * every shape the column holds the two must agree to the millisecond, in a zone
 * with summer time so that reading a zoneless value in the reader's own zone is
 * exercised too.
 */
class StoredMomentTest {

    private val madrid = TimeZone.getTimeZone("Europe/Madrid")

    @Test
    fun `the day count agrees with java time for four centuries`() {
        // Every day, not a sample: the arithmetic is era-based, and the places it
        // could be wrong are century years and the leap day, which a sample
        // would be lucky to land on.
        var date = LocalDate.of(1900, 1, 1)
        val end = LocalDate.of(2300, 12, 31)
        while (!date.isAfter(end)) {
            assertEquals(
                date.toEpochDay(),
                daysSinceEpoch(date.year, date.monthValue, date.dayOfMonth),
                "days to $date",
            )
            date = date.plusDays(1)
        }
    }

    @Test
    fun `agrees with the old parse on every shape the column holds`() {
        val random = Random(20261002)
        repeat(5_000) {
            val millis = random.nextLong(-2_208_988_800_000L, 7_258_118_400_000L) // 1900 to 2200
            for (shape in SHAPES) {
                val text = SimpleDateFormat(shape.pattern, Locale.ROOT)
                    .apply { timeZone = shape.writtenIn }
                    .format(Date(millis))

                assertEquals(oldParse(text, madrid)?.time, newParse(text, madrid), "reading $text")
            }
        }
    }

    @Test
    fun `refuses what is not a date rather than rolling it over`() {
        for (text in listOf(
            "2026-13-01",
            "2026-00-10",
            "2026-02-29",
            "2026-04-31",
            "2026-04-02T24:00:00Z",
            "2026-04-02T23:60:00Z",
            "2026-04-02T23:30:60Z",
            "2026-04-02T23:30:00+24:00",
            "2026-04-02T23:30:00.000+02:60",
        )) {
            assertNull(parseStoredMoment(text), text)
            assertNull(oldParse(text, madrid), "the old parse refused $text too")
        }
    }

    @Test
    fun `a leap day is a date in a leap year`() {
        assertNotNull(parseStoredMoment("2028-02-29"))
        assertNotNull(parseStoredMoment("2000-02-29"))
        assertNull(parseStoredMoment("2100-02-29"))
    }

    @Test
    fun `is narrower than SimpleDateFormat was, where nothing writes the difference`() {
        // SimpleDateFormat reads digits greedily, so it took these as dates. None
        // is a shape anything stores; each now comes back as itself, which is
        // how an unrecognised value is shown.
        for (text in listOf("26-1-5", "2026-4-2", "2026-04-02T23:30:00+0200", "2026-04-02T23:30:00.5Z")) {
            assertNull(parseStoredMoment(text), text)
        }
    }

    @Test
    fun `refuses a value that merely starts with a date, and forgives whitespace`() {
        assertNull(parseStoredMoment("2026-01-15 and 2026-02-20"))
        assertNull(parseStoredMoment("2026-04-02T23:30:00.000Z (imported)"))
        assertNotNull(parseStoredMoment("  2026-01-15\n"))
    }

    @Test
    fun `an offset fixes the instant, and its absence leaves it to the reader`() {
        val withOffset = parseStoredMoment("2026-04-02T23:30:00.000+02:00")!!
        assertEquals(1_775_165_400_000L, withOffset.epochMillis())

        assertNull(parseStoredMoment("2026-04-02T23:30:00")!!.epochMillis())
        assertNull(parseStoredMoment("2026-04-02")!!.epochMillis())
    }

    private class Shape(val pattern: String, val writtenIn: TimeZone)

    private val SHAPES = listOf(
        Shape("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", TimeZone.getTimeZone("UTC")),
        Shape("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC")),
        Shape("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", TimeZone.getTimeZone("America/St_Johns")),
        Shape("yyyy-MM-dd'T'HH:mm:ssXXX", TimeZone.getTimeZone("Asia/Kolkata")),
        Shape("yyyy-MM-dd'T'HH:mm:ss.SSS", madrid),
        Shape("yyyy-MM-dd'T'HH:mm:ss", madrid),
        Shape("yyyy-MM-dd", madrid),
    )

    private fun newParse(text: String, zone: TimeZone): Long? {
        val moment = parseStoredMoment(text) ?: return null
        return moment.epochMillis() ?: java.util.Calendar.getInstance(zone, Locale.ROOT).run {
            clear()
            set(moment.year, moment.month - 1, moment.day, moment.hour, moment.minute, moment.second)
            set(java.util.Calendar.MILLISECOND, moment.millisecond)
            timeInMillis
        }
    }

    /** StoredDates' parse before it became common code, unchanged. */
    private fun oldParse(value: String, timeZone: TimeZone): Date? {
        val text = value.trim()
        if (text.isEmpty()) return null

        for (pattern in listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
        )) {
            val format = SimpleDateFormat(pattern, Locale.ROOT).apply {
                isLenient = false
                this.timeZone = timeZone
            }
            val position = ParsePosition(0)
            val parsed = format.parse(text, position)
            if (parsed != null && position.index == text.length) return parsed
        }
        return null
    }
}
