package com.wardrobapp.presentation

import com.wardrobapp.data.DriveBackup
import com.wardrobapp.data.backupsToPrune
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The settings behind the weekly backup.
 *
 * Two of these decide what gets deleted out of somebody's Drive, so those are the
 * ones tested hardest: that "keep all" deletes nothing at all, and that every other
 * choice still defers to :data's counting rather than doing its own.
 *
 * The readers matter for a duller reason. They are what a preferences file is put
 * through on every launch, and a value they refused would be a backup schedule that
 * stopped working over a typo somebody could not see.
 */
class BackupSchedulingTest {

    private fun backup(id: String, at: Long) = DriveBackup(
        id = id,
        name = "wardrobapp-backup-$id.zip",
        modifiedAt = at,
    )

    /** Newest first is what Drive returns and what the pruning assumes. */
    private val six = listOf(
        backup("f", 600),
        backup("e", 500),
        backup("d", 400),
        backup("c", 300),
        backup("b", 200),
        backup("a", 100),
    )

    // ---- what gets deleted ---------------------------------------------------

    @Test
    fun `keeping all deletes nothing, however many there are`() {
        // The case :data cannot express. `backupsToPrune` treats a retention below
        // one as one, on purpose, so "keep all" cannot be spelled as a number --
        // asking it for zero would delete everything but the newest.
        assertEquals(emptyList(), backupsToRemove(six, BackupRetention.ALL))
    }

    @Test
    fun `keeping five drops the oldest and nothing else`() {
        assertEquals(listOf("a"), backupsToRemove(six, BackupRetention.FIVE))
    }

    @Test
    fun `keeping one leaves only the newest`() {
        // Offered knowing what it costs: a backup taken after the damage replaces
        // the one from before it. The screen says so where it is chosen.
        assertEquals(listOf("e", "d", "c", "b", "a"), backupsToRemove(six, BackupRetention.ONE))
    }

    @Test
    fun `a folder smaller than the retention loses nothing`() {
        assertEquals(emptyList(), backupsToRemove(six.take(3), BackupRetention.TEN))
    }

    @Test
    fun `an empty folder is not a reason to delete anything`() {
        for (retention in BackupRetention.entries) {
            assertEquals(emptyList(), backupsToRemove(emptyList(), retention), "$retention")
        }
    }

    @Test
    fun `every retention agrees with the counting in data`() {
        // The point of the wrapper is the ALL case. Everything else must still be
        // :data's answer rather than a second implementation that could drift.
        for (retention in BackupRetention.entries) {
            val keep = retention.keep ?: continue
            assertEquals(backupsToPrune(six, keep), backupsToRemove(six, retention), "$retention")
        }
    }

    // ---- reading what was stored ---------------------------------------------

    @Test
    fun `a stored frequency reads back as itself`() {
        for (frequency in BackupFrequency.entries) {
            assertEquals(frequency, backupFrequencyFor(frequency.storedValue))
        }
    }

    @Test
    fun `a stored retention reads back as itself`() {
        for (retention in BackupRetention.entries) {
            assertEquals(retention, backupRetentionFor(retention.storedValue))
        }
    }

    @Test
    fun `anything unreadable falls back to what the schedule shipped as`() {
        // A preferences file from a later build, or one somebody edited. Refusing it
        // would be a backup schedule that stopped over a value nobody can see.
        for (nonsense in listOf(null, "", "  ", "fortnightly", "7", "-1")) {
            assertEquals(BackupFrequency.WEEKLY, backupFrequencyFor(nonsense), "$nonsense")
        }
        for (nonsense in listOf(null, "", "  ", "everything", "0", "-3", "4")) {
            assertEquals(BackupRetention.FIVE, backupRetentionFor(nonsense), "$nonsense")
        }
    }

    @Test
    fun `stored values survive the casing and spacing a file might pick up`() {
        assertEquals(BackupFrequency.DAILY, backupFrequencyFor(" Daily "))
        assertEquals(BackupRetention.ALL, backupRetentionFor("ALL"))
    }

    @Test
    fun `an interval is given for every frequency`() {
        assertEquals(1L, BackupFrequency.DAILY.days)
        assertEquals(7L, BackupFrequency.WEEKLY.days)
        assertEquals(30L, BackupFrequency.MONTHLY.days)
    }
}
