package com.wardrobapp.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The timestamp format the rows hold.
 *
 * Exactly what JavaScript's `Date.toISOString()` produces, because the same
 * columns are sorted as text, and rows written by the app this replaced are
 * still in there: a row
 * written in a different shape would sort into the wrong place rather than
 * failing visibly. Always UTC, always three decimal places, always `Z`.
 *
 * `java.time` would express this better but needs API 26 or library desugaring,
 * and the app supports 24 -- so this is the older API, pinned to UTC and
 * `Locale.ROOT` so no device setting can change what gets written.
 */
private const val ISO_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

fun isoTimestamp(epochMillis: Long): String =
    SimpleDateFormat(ISO_PATTERN, Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(epochMillis))
