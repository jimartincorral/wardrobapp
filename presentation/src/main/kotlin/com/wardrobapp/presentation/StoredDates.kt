package com.wardrobapp.presentation

import java.text.DateFormat
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Turning a stored date into one worth showing.
 *
 * The column holds two shapes: `2026-01-15` from the date picker, and a full
 * `2026-04-02T10:11:12.000Z` from whenever the app stamped something itself.
 * Both have to render, and neither should ever reach the screen raw.
 *
 * Deliberately *not* a port of the TypeScript's `formatDate`, which produces
 * date-fns's English `MMM d, yyyy` on every device. Reproducing that on Android
 * would be worse than using the platform's own locale formatting, which is what
 * every other date on the phone looks like. Same input, better output.
 *
 * The timezone and locale arrive as arguments rather than being read from the
 * system, which is what makes this testable: a timestamp's *date* depends on the
 * zone it is read in, and that is exactly the part worth pinning down.
 */

/** The shapes the column is known to hold, tried in order. */
private val STORED_PATTERNS = listOf(
    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
    "yyyy-MM-dd'T'HH:mm:ssXXX",
    "yyyy-MM-dd'T'HH:mm:ss.SSS",
    "yyyy-MM-dd'T'HH:mm:ss",
    "yyyy-MM-dd",
)

/**
 * A stored date as the device would write it, or the raw string if it cannot be
 * read at all.
 *
 * Returning the input unchanged rather than throwing or showing a placeholder:
 * a date this does not recognise is still information, and a garment should not
 * fail to open over the shape of one field.
 */
fun formatStoredDate(
    value: String,
    timeZone: TimeZone,
    locale: Locale,
    style: Int = DateFormat.MEDIUM,
): String {
    val parsed = parseStoredDate(value, timeZone) ?: return value

    return DateFormat.getDateInstance(style, locale)
        .also { it.timeZone = timeZone }
        .format(parsed)
}

/**
 * The same, with the time of day kept.
 *
 * For the places where the date alone does not identify a thing: backups are
 * named by timestamp and several can share a day -- the rolling Drive folder
 * keeps five, and a wardrobe can be backed up twice in an afternoon. Choosing
 * between two rows both reading "28 August" is not choosing.
 *
 * Falls back the same way [formatStoredDate] does, and for the same reason: a
 * timestamp this cannot read is still information.
 */
fun formatStoredDateTime(
    value: String,
    timeZone: TimeZone,
    locale: Locale,
    dateStyle: Int = DateFormat.MEDIUM,
    timeStyle: Int = DateFormat.SHORT,
): String {
    val parsed = parseStoredDate(value, timeZone) ?: return value

    return DateFormat.getDateTimeInstance(dateStyle, timeStyle, locale)
        .also { it.timeZone = timeZone }
        .format(parsed)
}

private fun parseStoredDate(value: String, timeZone: TimeZone): Date? {
    val text = value.trim()
    if (text.isEmpty()) return null

    for (pattern in STORED_PATTERNS) {
        val format = SimpleDateFormat(pattern, Locale.ROOT).apply {
            isLenient = false
            // A date with no zone in it is a date in the reader's own zone; one
            // with a zone carries its own and this is ignored.
            this.timeZone = timeZone
        }
        val position = ParsePosition(0)
        val parsed = format.parse(text, position)

        // The whole string has to be consumed, or a value that merely *starts*
        // with a date is read as that date and the rest is discarded silently.
        // (The timestamp patterns come first, so a full timestamp never falls
        // through to the date-only one -- it is trailing content this catches.)
        if (parsed != null && position.index == text.length) return parsed
    }

    return null
}
