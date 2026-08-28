package com.wardrobapp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reading somebody's Drive folder, and deciding what to delete out of it.
 *
 * Two things could go wrong here and only one of them would be noticed. A listing
 * that stops parsing means cloud backups silently appear to have vanished, which
 * is alarming but recoverable. A prune that picks the wrong ids deletes somebody's
 * archives, which is not.
 *
 * So the deleting side is tested hardest: what it keeps, what it refuses to do
 * when asked to keep nothing, and that it never returns an id it was not given.
 */
class DriveBackupsTest {

    private val listing = """
        {
          "files": [
            {
              "id": "1aaa",
              "name": "wardrobapp-backup-2026-08-26T09-00-00-000Z.zip",
              "modifiedTime": "2026-08-26T09:00:00.000Z",
              "size": "9834042"
            },
            {
              "id": "2bbb",
              "name": "wardrobapp-backup-2026-08-28T09-00-00-000Z.zip",
              "modifiedTime": "2026-08-28T09:00:00.000Z",
              "size": "9836170"
            },
            {
              "id": "3ccc",
              "name": "wardrobapp-backup-2026-08-27T09-00-00-000Z.zip",
              "modifiedTime": "2026-08-27T09:00:00Z"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `a listing reads as backups, newest first`() {
        val backups = parseDriveBackups(listing)

        assertEquals(listOf("2bbb", "3ccc", "1aaa"), backups.map { it.id })
        assertEquals(9836170L, backups.first().bytes)
    }

    @Test
    fun `a timestamp without fractional seconds is still a timestamp`() {
        // Drive omits them when they are zero, which is exactly the case a
        // single-pattern parser would drop -- and dropping it would silently hide
        // one backup from the list.
        val backups = parseDriveBackups(listing)

        assertEquals(1, backups.count { it.id == "3ccc" })
    }

    @Test
    fun `files this app did not write are not ours to list`() {
        val foreign = """
            {
              "files": [
                {"id": "x", "name": "taxes.zip", "modifiedTime": "2026-08-28T09:00:00.000Z"},
                {"id": "y", "name": "wardrobapp-backup-2026-08-28T09-00-00-000Z.txt",
                 "modifiedTime": "2026-08-28T09:00:00.000Z"},
                {"id": "z", "name": "holiday-photos", "modifiedTime": "2026-08-28T09:00:00.000Z"}
              ]
            }
        """.trimIndent()

        assertTrue(parseDriveBackups(foreign).isEmpty())
    }

    @Test
    fun `an entry missing what it needs is dropped rather than guessed at`() {
        val ragged = """
            {
              "files": [
                {"name": "wardrobapp-backup-a.zip", "modifiedTime": "2026-08-28T09:00:00.000Z"},
                {"id": "", "name": "wardrobapp-backup-b.zip", "modifiedTime": "2026-08-28T09:00:00.000Z"},
                {"id": "c", "modifiedTime": "2026-08-28T09:00:00.000Z"},
                {"id": "d", "name": "wardrobapp-backup-d.zip"},
                {"id": "e", "name": "wardrobapp-backup-e.zip", "modifiedTime": "not a date"},
                {"id": "f", "name": "wardrobapp-backup-f.zip", "modifiedTime": "2026-08-28T09:00:00.000Z"}
              ]
            }
        """.trimIndent()

        assertEquals(listOf("f"), parseDriveBackups(ragged).map { it.id })
    }

    @Test
    fun `nothing readable is an empty list, not a crash`() {
        assertTrue(parseDriveBackups("").isEmpty())
        assertTrue(parseDriveBackups("not json").isEmpty())
        assertTrue(parseDriveBackups("{}").isEmpty())
        assertTrue(parseDriveBackups("""{"files": "no"}""").isEmpty())
        assertTrue(parseDriveBackups("""{"files": []}""").isEmpty())
    }

    @Test
    fun `a name is ours only with both the prefix and the extension`() {
        assertTrue(isBackupName("wardrobapp-backup-2026-08-28T09-00-00-000Z.zip"))
        assertFalse(isBackupName("wardrobapp-backup-2026-08-28.tar"))
        assertFalse(isBackupName("my-wardrobapp-backup-2026.zip"))
        assertFalse(isBackupName("wardrobe-backup.zip"))
    }

    @Test
    fun `a name that would escape the folder it is written into is not ours`() {
        // A restore writes the archive to a file named after the Drive entry, so a
        // name is a path before it is a label. Drive will hold any of these
        // happily -- its owner can rename a file in their own folder -- and the
        // prefix and extension alone do not make one safe to build a path from.
        assertFalse(isBackupName("wardrobapp-backup-../../../evil.zip"))
        assertFalse(isBackupName("wardrobapp-backup-/etc/passwd.zip"))
        assertFalse(isBackupName("wardrobapp-backup-..\\..\\evil.zip"))
        assertFalse(isBackupName("wardrobapp-backup-a\u0000b.zip"))
    }

    @Test
    fun `an escaping name is dropped from a listing entirely`() {
        // Not merely unlisted: it must not reach a DriveBackup at all, since
        // everything downstream trusts the name enough to write to it.
        val listing = """
            {"files": [
              {"id": "1", "name": "wardrobapp-backup-../../../evil.zip",
               "modifiedTime": "2026-08-28T09:00:00.000Z"},
              {"id": "2", "name": "wardrobapp-backup-2026-08-28T09-00-00-000Z.zip",
               "modifiedTime": "2026-08-28T09:00:00.000Z"}
            ]}
        """.trimIndent()

        val backups = parseDriveBackups(listing)

        assertEquals(listOf("2"), backups.map { it.id })
    }

    @Test
    fun `pruning keeps the newest and drops the oldest`() {
        val backups = parseDriveBackups(listing)

        assertEquals(listOf("1aaa"), backupsToPrune(backups, keep = 2))
        assertEquals(listOf("3ccc", "1aaa"), backupsToPrune(backups, keep = 1))
        assertTrue(backupsToPrune(backups, keep = 3).isEmpty())
        assertTrue(backupsToPrune(backups, keep = 10).isEmpty())
    }

    @Test
    fun `being asked to keep nothing keeps one anyway`() {
        // The alternative is a retention setting of zero quietly emptying somebody's
        // backup folder. A rule that deletes everything is a mistake, not an order.
        val backups = parseDriveBackups(listing)

        assertEquals(listOf("3ccc", "1aaa"), backupsToPrune(backups, keep = 0))
        assertEquals(listOf("3ccc", "1aaa"), backupsToPrune(backups, keep = -5))
    }

    @Test
    fun `pruning only ever names ids it was given`() {
        val backups = parseDriveBackups(listing)
        val known = backups.map { it.id }.toSet()

        for (keep in -1..4) {
            assertTrue(backupsToPrune(backups, keep).all { it in known })
        }
    }

    @Test
    fun `pruning an empty folder deletes nothing`() {
        assertTrue(backupsToPrune(emptyList(), keep = 3).isEmpty())
        assertTrue(backupsToPrune(emptyList(), keep = 0).isEmpty())
    }
}
