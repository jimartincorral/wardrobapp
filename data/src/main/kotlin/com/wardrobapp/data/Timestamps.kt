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

/**
 * The same format read back, and the shapes other people write it in.
 *
 * [isoTimestamp] is the only shape this app writes, but it is not the only shape
 * this app reads: Google Drive stamps a file's `modifiedTime` in RFC 3339, which
 * is the same timestamp with the fractional seconds omitted when they are zero,
 * and an archive manifest carries whichever shape the build that wrote it used.
 * So the patterns are tried in turn rather than one being assumed.
 *
 * Null rather than an exception, and null rather than a guess: a timestamp that
 * cannot be read is a record that gets dropped, which is safer than one that
 * sorts into the wrong place. Everything asking this question is ordering
 * backups by age, and the oldest is what gets deleted.
 */
fun epochMillisOfIso(text: String): Long? {
    for (pattern in READABLE_PATTERNS) {
        val parsed = try {
            SimpleDateFormat(pattern, Locale.ROOT)
                .apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    // So a near-miss is rejected rather than rolled over into a
                    // date nobody wrote -- "2026-13-01" is not next January.
                    isLenient = false
                }
                .parse(text)
        } catch (_: Exception) {
            null
        }

        if (parsed != null) return parsed.time
    }

    return null
}

/**
 * Every shape [epochMillisOfIso] accepts, most specific first.
 *
 * All UTC: this app writes `Z` and Drive returns `Z`, and a pattern that quietly
 * accepted a local time would read the same string as a different instant
 * depending on which phone was holding it.
 */
private val READABLE_PATTERNS = listOf(
    ISO_PATTERN,
    "yyyy-MM-dd'T'HH:mm:ss'Z'",
)
