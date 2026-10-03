package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.ArchivePreview
import com.wardrobapp.data.BackupSummary
import com.wardrobapp.data.MaintenanceSummary
import com.wardrobapp.data.readArchivePreview
import com.wardrobapp.presentation.SettingsScreenModel
import com.wardrobapp.presentation.SettingsScreenState
import com.wardrobapp.presentation.SettingsSource
import com.wardrobapp.presentation.StorageFigures
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Settings: what the wardrobe is using, and moving it in and out.
 *
 * The two halves are deliberately here together. Restoring used to live on the
 * wardrobe screen, which meant the app could take a backup in but not write one
 * out -- so anything done in this app had no way back to the one that ships.
 *
 * Neither half decides anything: what the numbers read as and how full the
 * progress bar is come from :presentation, and the archive itself from :data.
 *
 * What it does lives in SettingsScreenModel, in common code, so the browser can
 * run it too; this is the Android half: the scope that ends with the screen,
 * and PhoneSettingsSource as what does the work -- the photo store, the backup
 * writer and the restore, all through AppContainer.
 */
class SettingsViewModel(container: AppContainer) : ViewModel() {

    private val model = SettingsScreenModel(viewModelScope, PhoneSettingsSource(container))

    val state: StateFlow<SettingsScreenState> = model.state

    fun refresh() = model.refresh()
    fun onTidyRequested() = model.onTidyRequested()
    fun onTidyDismissed() = model.onTidyDismissed()

    /**
     * Write a backup into the file the user chose.
     *
     * Takes a way to open the destination rather than the destination itself, so
     * nothing here knows about content URIs -- and so the stream is opened on
     * the thread that writes to it. The same seam [onArchivePicked] uses in the
     * other direction.
     */
    fun onBackupDestinationPicked(openDestination: () -> OutputStream) =
        model.onBackupDestinationPicked(openDestination)

    fun onBackupDismissed() = model.onBackupDismissed()
    fun onRestoreRequested() = model.onRestoreRequested()
    fun onRestoreDismissed() = model.onRestoreDismissed()

    /**
     * Read the chosen archive and say what is in it, without applying it.
     *
     * Takes a way to *open* the archive rather than an open one, and that is what
     * makes this step possible at all: the preview consumes a stream and so does
     * the restore, and a `content://` stream does not rewind. A factory can be
     * called twice.
     */
    fun onArchivePicked(openArchive: () -> InputStream) = model.onArchivePicked(openArchive)

    fun onRestoreConfirmed(withSettings: Boolean) = model.onRestoreConfirmed(withSettings)
}

/**
 * The settings screen's source on the phone: the figures, the tidy, the backup
 * and the restore, each through AppContainer and each on Dispatchers.IO as the
 * ViewModel used to run them.
 *
 * Here rather than in :presentation with the other database sources, because
 * every part of it reaches past the database -- into the photo store, the
 * phone's own settings, and closing the database to stage or replace it -- and
 * those are AppContainer's. The server will have its own.
 */
private class PhoneSettingsSource(
    private val container: AppContainer,
) : SettingsSource<() -> InputStream, () -> OutputStream> {

    override suspend fun storage() = withContext(Dispatchers.IO) {
        StorageFigures(
            garments = container.garments.availableCount(),
            retired = container.garments.unavailableCount(),
            photoBytes = container.photoStorageBytes(),
        )
    }

    override suspend fun tidy(onProgress: (Int, Int) -> Unit): MaintenanceSummary =
        withContext(Dispatchers.IO) { container.tidyPhotos(onProgress) }

    override suspend fun backup(
        destination: () -> OutputStream,
        onProgress: (Int, Int) -> Unit,
    ): BackupSummary = withContext(Dispatchers.IO) { container.backupTo(destination, onProgress) }

    override suspend fun preview(archive: () -> InputStream): ArchivePreview =
        withContext(Dispatchers.IO) { archive().use { readArchivePreview(it) } }

    override suspend fun restore(archive: () -> InputStream, withSettings: Boolean) {
        withContext(Dispatchers.IO) { archive().use { container.restoreFrom(it, withSettings) } }
    }
}
