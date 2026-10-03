package com.wardrobapp.presentation

/**
 * A stored date, read: the fields it states, and its offset if it states one.
 *
 * The parsing half of StoredDates, in common code so that the phone and the
 * browser agree about what the column can hold. Formatting stays with each
 * platform -- the JVM's DateFormat on the phone, `Intl.DateTimeFormat` in the
 * browser -- because how a date is written for a reader is exactly what each
 * platform knows better than a copy would; which strings *are* dates is not,
 * and two parsers would be two answers.
 *
 * Deliberately narrow. It accepts the two shapes the column is known to hold --
 * `2026-01-15` from the date picker and `2026-04-02T10:11:12.000Z` from
 * whenever the app, or the React Native app before it, stamped something --
 * plus the variants of the second that drop the fraction, drop the zone, or give
 * an offset instead of `Z`. That is what the SimpleDateFormat patterns this
 * replaced were for, and it is narrower than what those patterns happened to
 * let through: SimpleDateFormat reads digits greedily, so `26-1-5` was a date
 * in the year 26 and `+0200` passed for an offset. Nothing writes those, and a
 * value that is not one of the known shapes comes back as itself, which is the
 * safe reading of something unrecognised.
 *
 * Validated as the old non-lenient parse was: a month past twelve, the 30th of
 * February or a 25th hour is not a date, rather than being rolled over into one
 * nobody wrote.
 */
internal class StoredMoment(
    val year: Int,
    /** 1 to 12, as written; not a zero-based calendar month. */
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val millisecond: Int,
    /**
     * Minutes east of UTC the value says it was written in, or null when it does
     * not say -- a bare date, or a timestamp with no zone -- in which case it is a
     * time in the reader's own zone, the only reading available.
     */
    val offsetMinutes: Int?,
) {
    /** The instant, when the value fixes one by stating its offset. */
    fun epochMillis(): Long? = offsetMinutes?.let { offset ->
        val days = daysSinceEpoch(year, month, day)
        val utc = (((days * 24 + hour) * 60 + minute) * 60 + second) * 1000 + millisecond
        utc - offset * 60_000L
    }
}

private val DATE = Regex("""(\d{4})-(\d{2})-(\d{2})""")

private val TIMESTAMP = Regex(
    """(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{3}))?(Z|([+-])(\d{2}):(\d{2}))?""",
)

/**
 * [value] read as a stored date, or null if it is not one.
 *
 * The whole string has to match, or a value that merely starts with a date is
 * read as that date and the rest is thrown away silently. Surrounding
 * whitespace is forgiven, as it was.
 */
internal fun parseStoredMoment(value: String): StoredMoment? {
    val text = value.trim()

    DATE.matchEntire(text)?.let { match ->
        val (year, month, day) = match.destructured
        return moment(year.toInt(), month.toInt(), day.toInt(), 0, 0, 0, 0, null)
    }

    val match = TIMESTAMP.matchEntire(text) ?: return null
    val groups = match.groupValues
    val offset = when {
        groups[8].isEmpty() -> null
        groups[8] == "Z" -> 0
        else -> {
            val hours = groups[10].toInt()
            val minutes = groups[11].toInt()
            // ISO 8601 and RFC 3339 allow offsets to ±23:59; every zone in use
            // sits within ±14:00. The wider bound is the one the format states.
            if (hours > 23 || minutes > 59) return null
            (hours * 60 + minutes) * (if (groups[9] == "-") -1 else 1)
        }
    }

    return moment(
        year = groups[1].toInt(),
        month = groups[2].toInt(),
        day = groups[3].toInt(),
        hour = groups[4].toInt(),
        minute = groups[5].toInt(),
        second = groups[6].toInt(),
        millisecond = groups[7].ifEmpty { "0" }.toInt(),
        offsetMinutes = offset,
    )
}

private fun moment(
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    second: Int,
    millisecond: Int,
    offsetMinutes: Int?,
): StoredMoment? {
    if (month !in 1..12) return null
    if (day !in 1..daysInMonth(year, month)) return null
    // No leap second: the old parse refused :60 too, and nothing writes one.
    if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
    return StoredMoment(year, month, day, hour, minute, second, millisecond, offsetMinutes)
}

private fun isLeapYear(year: Int) = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (isLeapYear(year)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

/**
 * Days from 1970-01-01 to a date in the proleptic Gregorian calendar.
 *
 * Howard Hinnant's days_from_civil, which counts in 400-year eras so that no
 * table and no loop is needed. Common code has no calendar library -- java.time
 * is the JVM's, and kotlinx-datetime would be a dependency for one function --
 * and this is the arithmetic those libraries do. StoredMomentTest checks it
 * against java.time for every day of four centuries.
 */
internal fun daysSinceEpoch(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val dayOfYear = (153 * ((month + 9) % 12) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097L + dayOfEra - 719_468
}

/**
 * [value] as a date, written the way the reader's own device or browser writes
 * dates, or [value] itself if it is not a stored date.
 *
 * For screens in common code, which have no zone or locale of their own to pass:
 * on the phone it is the device's, in the browser the browser's. The JVM's
 * [formatStoredDate], which takes both as arguments, is what the tests pin.
 */
expect fun formatStoredDateForReader(value: String): String

/** The same with the time of day kept, for things a date alone does not identify. */
expect fun formatStoredDateTimeForReader(value: String): String
