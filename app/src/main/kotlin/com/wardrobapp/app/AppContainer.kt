package com.wardrobapp.app

import com.wardrobapp.presentation.UndoHost
import android.content.Context
import com.wardrobapp.api.DirectoryPhotoFolder
import com.wardrobapp.api.PhoneSync
import com.wardrobapp.data.RecentlyDeleted
import com.wardrobapp.data.AnalyticsQueries
import com.wardrobapp.data.ArchiveBackup
import com.wardrobapp.data.ArchiveRestore
import com.wardrobapp.data.BackupSummary
import com.wardrobapp.data.Duplicates
import com.wardrobapp.data.Gaps
import com.wardrobapp.data.GarmentQueries
import com.wardrobapp.data.GarmentWrites
import com.wardrobapp.data.MaintenanceSummary
import com.wardrobapp.data.and
import com.wardrobapp.data.OutfitQueries
import com.wardrobapp.data.OutfitWrites
import com.wardrobapp.data.ReopeningDriver
import com.wardrobapp.data.Suggestions
import com.wardrobapp.data.StyleQueries
import com.wardrobapp.data.SyncStore
import com.wardrobapp.data.WardrobeSchema
import com.wardrobapp.domain.ImageFetcher
import com.wardrobapp.data.storedImageBytes
import com.wardrobapp.data.wardrobeFilesIn
import com.wardrobapp.net.HttpPageFetcher
import com.wardrobapp.net.ImportHttp
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Everything the screens need, built once.
 *
 * Deliberately plain: one object, constructed on first use, handed down. A
 * dependency-injection framework would be more machinery than four query
 * classes and a driver warrant.
 */
class AppContainer(context: Context) {

    /**
     * The database and the photo directory: everything the wardrobe *is* on disk.
     *
     * First, because everything below is derived from it, and from :data rather
     * than assembled here -- where these live is shared with the React Native app
     * and getting it wrong is silent. `wardrobeFilesIn` carries the reasoning.
     */
    private val files = wardrobeFilesIn(context.filesDir)

    /**
     * The connection, reopened on demand.
     *
     * Through [ReopeningDriver] rather than opened once, because a restore
     * replaces the file this is holding. The schema is applied on every open,
     * exactly as the TypeScript client does -- including the open that follows a
     * restore, which may have installed a database written by an older build.
     */
    private val database = ReopeningDriver {
        AndroidSqlDriver.open(context, files.databaseFile.absolutePath)
            .also { WardrobeSchema.applyTo(it) }
    }

    /**
     * Where garment photos live, as a URI.
     *
     * The database stores bare filenames, so this is re-attached on read. The
     * `file://` prefix and trailing separator match what the React Native app
     * produced, since `resolveImageRef` concatenates directly onto it -- and
     * Coil loads a file:// URI directly.
     */
    val imageDirectory: String = "file://${files.imagesDir.absolutePath}/"

    val garments = GarmentQueries(database, imageDirectory)
    val garmentWrites = GarmentWrites(database)
    val outfits = OutfitQueries(database)
    val outfitWrites = OutfitWrites(database)
    val analytics = AnalyticsQueries(database)
    val suggestions = Suggestions(garments, outfits, StyleQueries(database))
    val duplicates = Duplicates(garments)
    val gaps = Gaps(garments, outfits)

    /**
     * How the wardrobe is drawn, and where that survives a restart.
     *
     * Held here rather than read in the screen so the list's first frame is the
     * layout that was chosen: a grid arriving after a list is a visible jump.
     */
    val wardrobeView = WardrobeViewPreference(context)

    /** What a backup carries about how the app is set up, and what puts it back. */
    val appSettings = AppSettings(context)

    /** Where photos are decoded, scaled and written. */
    val photos = AndroidPhotoStore(context)

    /** Cutting a garment out of its background, on device. */
    val backgrounds = AndroidBackgroundRemover(context, photos)

    /** What the photo directory has been through; see [frameCutoutsOnce]. */
    private val photoMaintenance = PhotoMaintenancePreference(context)

    /** The requests URL import makes, for pages and images alike. */
    private val importHttp = ImportHttp()

    /**
     * Downloading a product page's images into the wardrobe.
     *
     * Held here because it is stateless; the page fetcher below is not -- it owns
     * one connection at a time -- so that one is handed out fresh per import.
     */
    val importImages: ImageFetcher = AndroidImageFetcher(context, importHttp, photos, imageDirectory)

    /** A fetcher for one page. Closed by the caller. */
    fun importPages(): HttpPageFetcher = importHttp.pages()

    private val restore = ArchiveRestore(
        files = files,
        // The same volume as the wardrobe, so installing the extracted archive
        // is a rename rather than a second copy of every photo.
        workRoot = context.cacheDir,
        databaseCheck = AndroidStagedDatabaseCheck(context),
    )

    private val backup = ArchiveBackup(files = files, workRoot = context.cacheDir)

    /**
     * Work that outlives the screen that started it: a sync carries on when
     * somebody leaves Settings, or rotates the phone while the app is opening.
     * Never cancelled, like the process it belongs to.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The deletes that can still be undone, and the line that offers them.
     * One of each for the process, as the database is: the screen that
     * deleted a garment is gone by the time Undo is tapped, and the line is
     * drawn by the activity's shell over whatever screen is up. See
     * RecentlyDeleted and UndoHost.
     */
    val recentlyDeleted = RecentlyDeleted()
    val undo = UndoHost(appScope)

    /**
     * Syncing with Home Assistant, if this phone is paired with one.
     *
     * One for the process, as the database is, and for a reason of its own:
     * it is what stops two syncs running at once, and what Settings watches to
     * show one running, so Settings, opening the app and the background worker
     * must all reach this same one.
     *
     * Over the same connection and the same photo directory as everything
     * else, so what a sync brings is simply part of the wardrobe -- there is no
     * second copy to reconcile.
     */
    val sync = PhoneSync(
        preferences = SharedPreferencesSyncSettings(context),
        store = SyncStore(database),
        photos = DirectoryPhotoFolder(files.imagesDir),
        background = WorkManagerBackgroundSync(context),
        // A wardrobe that arrived by sync has no first steps to take, as one
        // that arrived by restore has not; see Onboarding in MainActivity
        // for the restore's half of this.
        onWardrobeArrived = { OnboardingPreference(context).firstStepsDismissed = true },
    )

    /**
     * Sync because the app came to the front, unless PhoneSync decides there is
     * no point -- see [PhoneSync.syncOnOpen]. In [appScope], so it is not
     * cancelled by the activity going away under it.
     */
    fun syncOnOpen(metered: Boolean) {
        appScope.launch { sync.syncOnOpen(metered) }
    }

    /** How much disk the wardrobe's photos take, for the settings screen. */
    fun photoStorageBytes(): Long = storedImageBytes(files.imagesDir)

    /**
     * Tidy the photo directory: shrink what is oversized, delete what is orphaned.
     *
     * Two passes, because two different things accumulate. Cut-outs written at full
     * resolution by an older build are shrunk in place, which touches no row. Files
     * nothing points at are deleted -- an original whose cut-out replaced it under a
     * build that kept both, a photo left behind by a save that died between writing
     * the row and deleting the file.
     *
     * The second pass is the one that needs the database, and it is why this lives
     * here rather than on the store: what is referenced is a question only the
     * wardrobe can answer. Every garment is asked, retired ones included --
     * `availableOnly = false` rather than the default, which means available only.
     * Sweeping a retired garment's photos would take away exactly the thing that
     * makes retiring reversible.
     */
    fun tidyPhotos(onProgress: (Int, Int) -> Unit): MaintenanceSummary {
        val shrunk = photos.shrinkOversizedCutouts(onProgress)

        // A third pass, framing cut-outs around their garment, which is here
        // as well as run once on its own (see [frameCutoutsOnce]) so that a
        // cut-out that pass could not read, or a wardrobe restored from a
        // backup made before cut-outs were framed, has a way to catch up.
        val framed = photos.frameLooseCutouts(onProgress)

        val referenced = garments
            .allGarments(GarmentQueries.Filters(availableOnly = false))
            .flatMap { it.displayImageUris + it.displayNoBgImageUris }

        return shrunk.and(framed).and(photos.deleteUnreferenced(referenced, onProgress = onProgress))
    }

    /**
     * Frame every cut-out already on the phone around its garment, once.
     *
     * Cut-outs made before CutoutFraming existed keep the whole photo's
     * canvas, with the garment wherever it was in it, and a wardrobe of a few
     * hundred garments is not going to be re-cut by hand. So the first start
     * of a build that frames them frames what is there, in the background,
     * and remembers having done so. In [appScope] rather than the activity's,
     * as the sync is: a pass through a hundred PNGs outlasts a screen.
     *
     * What is on screen during that first start may still show the old
     * framing: the image loader keeps what it has decoded, and a file
     * rewritten underneath it is read again only when it is next asked for.
     * The next start shows every cut-out framed.
     */
    fun frameCutoutsOnce() {
        if (photoMaintenance.cutoutsFramed) return

        appScope.launch(Dispatchers.IO) {
            photos.frameLooseCutouts()
            photoMaintenance.cutoutsFramed = true
        }
    }

    /**
     * Replace the wardrobe with the contents of a backup archive.
     *
     * The connection is closed for the duration: the file it is holding is the
     * file being replaced. Throws [com.wardrobapp.data.UnrestorableArchiveException]
     * with something worth showing the user if the archive cannot be restored --
     * and in that case nothing has changed.
     */
    fun restoreFrom(archive: InputStream, applySettings: Boolean = false) {
        // Under the sync's lock: see PhoneSync.restoring, which also marks the
        // restored wardrobe to replace the one in Home Assistant on a phone
        // that syncs. Blocking, as the rest of this does: it is called on IO.
        val settings = runBlocking {
            sync.restoring { database.whileClosed { restore.restoreFromZip(archive) } }
        }

        // Outside `whileClosed`, and after it: preferences have nothing to do with
        // the database connection, and applying the language restarts activities.
        // Doing that while the connection is closed would have the app come back
        // up and read a database that is not open yet.
        if (applySettings && settings != null) appSettings.apply(settings)

        // On a phone that syncs, the restored wardrobe replaces the one in Home
        // Assistant too -- otherwise the next sync would merge it with the
        // wardrobe it was meant to replace, and every edit since the backup
        // would win. Sent at once, from the app's scope so leaving the screen
        // does not cancel it.
        if (sync.status.value.paired) appScope.launch { sync.sync() }
    }

    /**
     * Write the wardrobe out as a backup archive.
     *
     * Only the staging is done with the connection closed. Holding it closed for
     * the whole write would mean the app could not read its own wardrobe for as
     * long as the archive takes, which is as long as the wardrobe is large --
     * and photos are immutable once written, so there is nothing to protect them
     * from.
     *
     * The destination is opened by the writer, not here: staging comes first and
     * can fail, and opening before that would leave an empty document behind.
     */
    fun backupTo(
        openDestination: () -> OutputStream,
        onImageCopied: (Int, Int) -> Unit,
    ): BackupSummary {
        val staged = database.whileClosed { backup.stageDatabase() }

        // Read here rather than inside the writer, so that :data stays a module
        // that knows the archive format and nothing about Android preferences.
        val settings = appSettings.capture()

        return try {
            backup.writeArchive(openDestination, staged, settings, onImageCopied)
        } finally {
            backup.discardStaging()
        }
    }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        /**
         * The one container for the process.
         *
         * Opening SQLite twice against the same file is exactly the hazard the
         * React Native app had to add a maintenance lock for, so there is one
         * connection and everything shares it.
         */
        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
    }
}
