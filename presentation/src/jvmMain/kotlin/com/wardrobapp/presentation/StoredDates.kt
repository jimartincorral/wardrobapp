package com.wardrobapp.presentation

import java.text.DateFormat
import java.util.Calendar
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

/**
 * The parse is common code, shared with the browser -- see StoredMoment. What is
 * left here is turning it into an instant on the JVM: one the value fixes by
 * stating its offset, or the fields read as a time in [timeZone] when it does
 * not say.
 */
private fun parseStoredDate(value: String, timeZone: TimeZone): Date? {
    val moment = parseStoredMoment(value) ?: return null
    moment.epochMillis()?.let { return Date(it) }

    return Calendar.getInstance(timeZone, Locale.ROOT).run {
        clear()
        set(moment.year, moment.month - 1, moment.day, moment.hour, moment.minute, moment.second)
        set(Calendar.MILLISECOND, moment.millisecond)
        time
    }
}

actual fun formatStoredDateForReader(value: String): String =
    formatStoredDate(value, TimeZone.getDefault(), Locale.getDefault())

actual fun formatStoredDateTimeForReader(value: String): String =
    formatStoredDateTime(value, TimeZone.getDefault(), Locale.getDefault())
