package com.wardrobapp.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsViewTest {

    // ---- the storage rows ----------------------------------------------------

    @Test
    fun `it reports the counts it was given`() {
        val view = settingsView(garments = 12, retired = 3, photoBytes = 0)
        assertEquals(12L, view.garments)
        assertEquals(3L, view.retired)
    }

    @Test
    fun `an empty wardrobe uses no megabytes`() {
        assertEquals("0.0", formatMegabytes(0))
    }

    @Test
    fun `a wardrobe smaller than a megabyte does not round away to nothing`() {
        // 512 KB. Whole megabytes would call this zero, which reads as a bug.
        assertEquals("0.5", formatMegabytes(512L * 1024))
    }

    @Test
    fun `it divides by 1024 twice, not once`() {
        assertEquals("1.0", formatMegabytes(1024L * 1024))
        assertEquals("12.5", formatMegabytes((12.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun `it rounds rather than truncating`() {
        // 1.96 MB: truncation would say 1.9.
        assertEquals("2.0", formatMegabytes((1.96 * 1024 * 1024).toLong()))
    }

    @Test
    fun `a large wardrobe keeps one decimal place`() {
        val text = formatMegabytes(3L * 1024 * 1024 * 1024)
        assertEquals("3072.0", text)
        assertEquals(1, text.substringAfter('.').length)
    }

    @Test
    fun `the view formats the bytes it was given`() {
        assertEquals("2.0", settingsView(0, 0, 2L * 1024 * 1024).photoMegabytes)
    }

    // ---- the progress bar ---------------------------------------------------

    @Test
    fun `staging is where the bar starts`() {
        assertEquals(5, backupPercent(BackupPhase.STAGING, 0, 0))
    }

    @Test
    fun `archiving fills the rest of the track`() {
        assertEquals(5, backupPercent(BackupPhase.ARCHIVING, 0, 10))
        assertEquals(53, backupPercent(BackupPhase.ARCHIVING, 5, 10))
        assertEquals(100, backupPercent(BackupPhase.ARCHIVING, 10, 10))
    }

    @Test
    fun `a wardrobe with no photos does not divide by zero`() {
        assertEquals(5, backupPercent(BackupPhase.ARCHIVING, 0, 0))
    }

    @Test
    fun `done is done`() {
        assertEquals(100, backupPercent(BackupPhase.DONE, 0, 0))
    }

    @Test
    fun `the bar never goes backwards as photos are copied`() {
        val total = 37
        var previous = backupPercent(BackupPhase.STAGING, 0, total)
        for (copied in 0..total) {
            val now = backupPercent(BackupPhase.ARCHIVING, copied, total)
            assertTrue(now >= previous, "went backwards at $copied: $previous -> $now")
            previous = now
        }
        assertEquals(100, previous)
    }

    @Test
    fun `the bar stays within its track however it is called`() {
        val calls = listOf(
            Triple(BackupPhase.ARCHIVING, -5, 10),
            Triple(BackupPhase.ARCHIVING, 50, 10),
            Triple(BackupPhase.ARCHIVING, 0, -1),
            Triple(BackupPhase.STAGING, 99, 1),
            Triple(BackupPhase.DONE, -1, -1),
        )
        for ((phase, copied, total) in calls) {
            val percent = backupPercent(phase, copied, total)
            assertTrue(percent in 0..100, "$phase $copied/$total gave $percent")
        }
    }
}
