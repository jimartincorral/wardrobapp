package com.wardrobapp.presentation

import com.wardrobapp.data.DriveBackup
import com.wardrobapp.data.backupsToPrune

/**
 * What somebody has asked the weekly backup to do.
 *
 * Pure, and here rather than in :app for the reason the destructive half makes
 * obvious: "how many backups to keep" decides what gets deleted out of somebody's
 * Drive, and that is not a decision to leave somewhere no test can reach.
 *
 * The shape is [ThemeChoice]'s and [LanguageChoice]'s: an enum, a value to store,
 * and a reader that falls back rather than failing. Anything unrecognised -- a
 * value written by a later build, a preferences file that has been edited -- reads
 * as the default rather than as an error, because the alternative is a backup
 * schedule that stops working over a typo.
 */

/** How often a backup runs. */
enum class BackupFrequency {
    DAILY,
    WEEKLY,
    MONTHLY,
}

/**
 * How long between runs.
 *
 * A month is thirty days rather than a calendar month: the schedule repeats on an
 * interval and has no notion of which month it is in, and pretending otherwise
 * would mean explaining why a backup happened on the 28th.
 */
val BackupFrequency.days: Long
    get() = when (this) {
        BackupFrequency.DAILY -> 1L
        BackupFrequency.WEEKLY -> 7L
        BackupFrequency.MONTHLY -> 30L
    }

val BackupFrequency.storedValue: String
    get() = when (this) {
        BackupFrequency.DAILY -> "daily"
        BackupFrequency.WEEKLY -> "weekly"
        BackupFrequency.MONTHLY -> "monthly"
    }

/** Weekly for anything unrecognised, which is what the schedule shipped as. */
fun backupFrequencyFor(stored: String?): BackupFrequency =
    when (stored?.trim()?.lowercase()) {
        "daily" -> BackupFrequency.DAILY
        "monthly" -> BackupFrequency.MONTHLY
        else -> BackupFrequency.WEEKLY
    }

/** How many archives the Drive folder keeps. */
enum class BackupRetention {
    ONE,
    THREE,
    FIVE,
    TEN,
    ALL,
}

/**
 * The number to keep, or null for all of them.
 *
 * Null rather than a very large number, because "never delete" is a different
 * instruction from "delete once there are more than a thousand" and the code that
 * deletes should be able to tell them apart.
 *
 * [ONE] is offered knowing what it gives up: keeping a single archive means a
 * backup taken after the damage replaces the one from before it, which is the
 * failure a rolling backup exists to prevent. Somebody who wants a mirror rather
 * than a history is asking for that on purpose, and the screen says so where it is
 * chosen.
 */
val BackupRetention.keep: Int?
    get() = when (this) {
        BackupRetention.ONE -> 1
        BackupRetention.THREE -> 3
        BackupRetention.FIVE -> 5
        BackupRetention.TEN -> 10
        BackupRetention.ALL -> null
    }

val BackupRetention.storedValue: String
    get() = when (this) {
        BackupRetention.ALL -> "all"
        else -> keep.toString()
    }

/** Five for anything unrecognised, which is what the schedule shipped as. */
fun backupRetentionFor(stored: String?): BackupRetention =
    when (stored?.trim()?.lowercase()) {
        "all" -> BackupRetention.ALL
        "1" -> BackupRetention.ONE
        "3" -> BackupRetention.THREE
        "10" -> BackupRetention.TEN
        else -> BackupRetention.FIVE
    }

/**
 * Which archives to delete, for a given retention.
 *
 * The whole reason this exists rather than an `if` at the call site:
 * [backupsToPrune] cannot express "never delete" -- it treats a retention below
 * one as one, deliberately, because a rule that empties the backup folder is a
 * misconfiguration rather than an instruction. That guard is right for a number
 * and wrong for [BackupRetention.ALL], which is not a number at all.
 *
 * So the two live together here, where a test can hold both: the counting is still
 * :data's, and the one case it has no answer for is answered once.
 */
fun backupsToRemove(
    backups: List<DriveBackup>,
    retention: BackupRetention,
): List<String> = retention.keep?.let { backupsToPrune(backups, it) } ?: emptyList()
