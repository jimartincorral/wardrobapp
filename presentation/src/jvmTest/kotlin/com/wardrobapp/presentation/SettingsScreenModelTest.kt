package com.wardrobapp.presentation

import com.wardrobapp.data.ArchivePreview
import com.wardrobapp.data.BackupSummary
import com.wardrobapp.data.MaintenanceSummary
import com.wardrobapp.data.UnrestorableArchiveException
import com.wardrobapp.data.UnrestorableReason
import com.wardrobapp.presentation.SettingsScreenState.Backup
import com.wardrobapp.presentation.SettingsScreenState.Restore
import com.wardrobapp.presentation.SettingsScreenState.Tidy
import java.io.IOException
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Archives and destinations are strings here: the model never looks inside
 * either, which is the property the generic parameters exist to keep.
 */
class SettingsScreenModelTest {

    private class FakeSource : SettingsSource<String, String> {
        var garments = 10L
        var tidyResult = MaintenanceSummary(examined = 4, shrunk = 0, bytesSaved = 0)
        var unrestorable = setOf<String>()
        var failBackup: Exception? = null
        val backedUpTo = mutableListOf<String>()
        val restored = mutableListOf<Pair<String, Boolean>>()

        override suspend fun storage() = StorageFigures(garments, retired = 2, photoBytes = 3_000_000)

        override suspend fun tidy(onProgress: (Int, Int) -> Unit): MaintenanceSummary {
            onProgress(1, 4)
            return tidyResult
        }

        override suspend fun backup(destination: String, onProgress: (Int, Int) -> Unit): BackupSummary {
            failBackup?.let { throw it }
            onProgress(1, 2)
            backedUpTo += destination
            return BackupSummary(bytes = 1_500_000, images = 2, skipped = 0)
        }

        override suspend fun preview(archive: String): ArchivePreview {
            if (archive in unrestorable) throw UnrestorableArchiveException(UnrestorableReason.ManifestNotFound(archive))
            return ArchivePreview(version = 1, presentImages = 3, hasDatabase = true)
        }

        override suspend fun restore(archive: String, withSettings: Boolean) {
            restored += archive to withSettings
            garments = 42
        }
    }

    @Test
    fun `reads the storage figures`() = runTest {
        val model = SettingsScreenModel(this, FakeSource())
        advanceUntilIdle()

        assertEquals(settingsView(garments = 10, retired = 2, photoBytes = 3_000_000), model.state.value.view)
    }

    @Test
    fun `a tidy that changed nothing says so`() = runTest {
        val model = SettingsScreenModel(this, FakeSource())
        advanceUntilIdle()

        model.onTidyRequested()
        advanceUntilIdle()

        assertEquals(Tidy.NothingToDo(examined = 4), model.state.value.tidy)
    }

    @Test
    fun `a tidy that freed space reports it and re-reads the figures`() = runTest {
        val source = FakeSource().apply {
            tidyResult = MaintenanceSummary(examined = 4, shrunk = 1, bytesSaved = 2_000_000, deleted = 2)
        }
        val model = SettingsScreenModel(this, source)
        advanceUntilIdle()
        source.garments = 9

        model.onTidyRequested()
        advanceUntilIdle()

        assertEquals(Tidy.Done(tidied = 3, reclaimed = 2, megabytes = formatMegabytes(2_000_000)), model.state.value.tidy)
        assertEquals(9, model.state.value.view?.garments)
    }

    @Test
    fun `a backup goes to the chosen destination and reports what it held`() = runTest {
        val source = FakeSource()
        val model = SettingsScreenModel(this, source)
        advanceUntilIdle()

        model.onBackupDestinationPicked("backup.zip")
        advanceUntilIdle()

        assertEquals(listOf("backup.zip"), source.backedUpTo)
        assertEquals(Backup.Done(megabytes = formatMegabytes(1_500_000), photos = 2, skipped = 0), model.state.value.backup)
    }

    @Test
    fun `a failed backup says why`() = runTest {
        val source = FakeSource().apply { failBackup = IOException("no space") }
        val model = SettingsScreenModel(this, source)
        advanceUntilIdle()

        model.onBackupDestinationPicked("backup.zip")
        advanceUntilIdle()

        assertEquals(Backup.Failed("no space"), model.state.value.backup)
    }

    @Test
    fun `an archive is previewed first and restored only once confirmed`() = runTest {
        val source = FakeSource()
        val model = SettingsScreenModel(this, source)
        advanceUntilIdle()

        model.onRestoreRequested()
        model.onArchivePicked("wardrobe.zip")
        advanceUntilIdle()
        assertIs<Restore.Previewing>(model.state.value.restore)
        assertEquals(emptyList(), source.restored)

        model.onRestoreConfirmed(withSettings = true)
        advanceUntilIdle()

        assertEquals(listOf("wardrobe.zip" to true), source.restored)
        assertEquals(Restore.Done(garments = 42), model.state.value.restore)
    }

    @Test
    fun `an archive that cannot be restored is refused at the preview, with its reason`() = runTest {
        val source = FakeSource().apply { unrestorable = setOf("photo.zip") }
        val model = SettingsScreenModel(this, source)
        advanceUntilIdle()

        model.onArchivePicked("photo.zip")
        advanceUntilIdle()
        val failed = assertIs<Restore.Failed>(model.state.value.restore)
        assertEquals(UnrestorableReason.ManifestNotFound("photo.zip"), failed.reason)

        model.onRestoreConfirmed(withSettings = false)
        advanceUntilIdle()
        assertEquals(emptyList(), source.restored, "a refused archive is not left armed for confirmation")
    }

    @Test
    fun `backing out of a preview disarms the archive`() = runTest {
        val source = FakeSource()
        val model = SettingsScreenModel(this, source)
        advanceUntilIdle()

        model.onArchivePicked("wardrobe.zip")
        advanceUntilIdle()
        model.onRestoreDismissed()
        model.onRestoreConfirmed(withSettings = false)
        advanceUntilIdle()

        assertEquals(emptyList(), source.restored)
        assertNull(model.state.value.restore)
    }
}
