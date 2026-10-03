package com.wardrobapp.presentation

/**
 * Stored dates in the browser: the common parse, then the browser's own
 * formatting.
 *
 * `Intl.DateTimeFormat` with no locale is the page's, and it formats in the
 * browser's time zone -- the counterpart of the JVM's `Locale.getDefault()` and
 * `TimeZone.getDefault()`, so a date reads in the browser the way every other
 * date on that machine does, which is the same reasoning StoredDates gives for
 * not porting date-fns. Medium dates and short times, as on the phone.
 *
 * Made once and reused, like the collator in ReaderOrder: constructing a
 * formatter is the expensive part, and a list of backups formats one per row.
 */
private val dateFormat: JsAny = js("new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' })")

private val dateTimeFormat: JsAny =
    js("new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })")

private fun format(formatter: JsAny, date: JsAny): String = js("formatter.format(date)")

private fun instant(epochMillis: Double): JsAny = js("new Date(epochMillis)")

/**
 * The fields as a time in the browser's own zone, for a value that gives none.
 *
 * Through setFullYear rather than the `new Date(year, ...)` constructor, which
 * reads a year below 100 as 1900 plus it.
 */
private fun local(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, millisecond: Int): JsAny =
    js("{ const d = new Date(0); d.setFullYear(year, month - 1, day); d.setHours(hour, minute, second, millisecond); return d; }")

private fun StoredMoment.toJsDate(): JsAny =
    epochMillis()?.let { instant(it.toDouble()) }
        ?: local(year, month, day, hour, minute, second, millisecond)

actual fun formatStoredDateForReader(value: String): String =
    parseStoredMoment(value)?.let { format(dateFormat, it.toJsDate()) } ?: value

actual fun formatStoredDateTimeForReader(value: String): String =
    parseStoredMoment(value)?.let { format(dateTimeFormat, it.toJsDate()) } ?: value
