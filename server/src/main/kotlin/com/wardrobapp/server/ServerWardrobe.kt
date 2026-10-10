package com.wardrobapp.server

import com.wardrobapp.api.Routes
import com.wardrobapp.data.AnalyticsQueries
import com.wardrobapp.data.Duplicates
import com.wardrobapp.data.Gaps
import com.wardrobapp.data.GarmentQueries
import com.wardrobapp.data.GarmentWrites
import com.wardrobapp.data.JdbcSqlDriver
import com.wardrobapp.data.OutfitQueries
import com.wardrobapp.data.OutfitWrites
import com.wardrobapp.data.RecentlyDeleted
import com.wardrobapp.data.ReopeningDriver
import com.wardrobapp.data.StyleQueries
import com.wardrobapp.data.Suggestions
import com.wardrobapp.data.SyncStore
import com.wardrobapp.data.WardrobeSchema
import com.wardrobapp.data.cutoutFilename
import com.wardrobapp.data.resolveImageRef
import com.wardrobapp.data.storedImageBytes
import com.wardrobapp.data.toStoredImageRef
import com.wardrobapp.data.wardrobeFilesIn
import com.wardrobapp.domain.ImageFetcher
import com.wardrobapp.net.ImportHttp
import com.wardrobapp.presentation.DatabaseBulkAddSource
import com.wardrobapp.presentation.DatabaseGarmentDetailSource
import com.wardrobapp.presentation.DatabaseGarmentFormSource
import com.wardrobapp.presentation.DatabaseHomeSource
import com.wardrobapp.presentation.DatabaseInspirationSource
import com.wardrobapp.presentation.DatabaseOutfitDetailSource
import com.wardrobapp.presentation.DatabaseOutfitEditSource
import com.wardrobapp.presentation.DatabaseOutfitsSource
import com.wardrobapp.presentation.DatabaseStatisticsSource
import com.wardrobapp.presentation.DatabaseWardrobeSource
import com.wardrobapp.presentation.FetchingGarmentImporter
import com.wardrobapp.presentation.GarmentImporter
import com.wardrobapp.presentation.StorageFigures
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One wardrobe, held by the server: its database, its photos, and every
 * screen's source over them.
 *
 * The server's AppContainer, and built the same way: the same files in the
 * same layout under [dataDirectory] -- `/data` in the Home Assistant app, the
 * directory Home Assistant keeps and backs up for it -- the schema applied on
 * every open, and :presentation's Database sources on top. So a wardrobe moved
 * between the two, one day, is the same files in the same places.
 *
 * One difference, and it is the one that makes photos work in a browser. The
 * phone reads a photo reference as an absolute `file://` path; the server
 * reads it as `photos/<name>` ([Routes.PHOTO_FILES]), a path relative to the
 * page, which the server answers with the file. Nothing else changes: rows
 * store bare names on both, the write paths reduce any reference to its name
 * before storing it, and the queries already take the directory to resolve
 * against as a parameter -- it was a parameter so that two apps could disagree
 * about it.
 *
 * [photoPrefix] is that path: `photos/` for a wardrobe answered at the root,
 * `p/<id>/photos/` for one of several profiles (see Profiles), so a reference
 * names the profile its photo belongs to and still resolves against the page.
 *
 * [importer] is for tests, which have no internet to import from.
 * [backgrounds] cuts photos out, or is null where there is no model to; see
 * BackgroundRemover.
 */
class ServerWardrobe(
    dataDirectory: File,
    importer: GarmentImporter? = null,
    private val backgrounds: BackgroundRemover? = null,
    /** The style model, shared by every wardrobe; null where there is none. See StyleIndex. */
    styleEncoder: StyleEncoder? = null,
    private val photoPrefix: String = Routes.PHOTO_FILES,
    /** The code a phone pairs with, kept beside the wardrobe it opens; see [syncSecretIn]. */
    val syncSecret: SyncSecret = syncSecretIn(dataDirectory),
) : AutoCloseable {

    private val files = wardrobeFilesIn(dataDirectory)

    private val database = ReopeningDriver {
        JdbcSqlDriver.open(files.databaseFile).also { WardrobeSchema.applyTo(it) }
    }

    val photos = PhotoFiles(files.imagesDir)

    private val garments = GarmentQueries(database, photoPrefix)
    private val garmentWrites = GarmentWrites(database)
    private val outfits = OutfitQueries(database)
    private val outfitWrites = OutfitWrites(database)
    private val duplicates = Duplicates(garments)
    internal val styleQueries = StyleQueries(database)

    private val io = Dispatchers.IO

    /**
     * What the style model has said about this wardrobe and keeps saying;
     * null on a server without the model, which the browser is told (see
     * [learnsStyle]) so it does not offer what nothing would use.
     */
    val style: StyleIndex? = styleEncoder?.let { StyleIndex(it, photos, styleQueries, garments, garmentWrites) }

    /** Whether photos of looks the reader likes would teach this server anything. */
    val learnsStyle: Boolean get() = style != null

    val inspirations = DatabaseInspirationSource(styleQueries, photoPrefix, photos::delete, io)

    /**
     * The deletes that can still be undone, shared by every source that
     * deletes from this wardrobe: an outfit deleted from the list and undone
     * from the same list, or from its own screen, is the same memory.
     */
    private val recentlyDeleted = RecentlyDeleted()

    val home = DatabaseHomeSource(garments, outfits, io)
    val outfitDetail = DatabaseOutfitDetailSource(outfits, outfitWrites, garments, io, recentlyDeleted)
    val outfitEdit = DatabaseOutfitEditSource(garments, outfits, outfitWrites, io)
    val wardrobe = DatabaseWardrobeSource(garments, io)
    val statistics = DatabaseStatisticsSource(
        garments = garments,
        analytics = AnalyticsQueries(database),
        duplicates = duplicates,
        gaps = Gaps(garments, outfits),
        imageDirectory = photoPrefix,
        io = io,
    )
    val outfitList = DatabaseOutfitsSource(garments, outfits, outfitWrites, Suggestions(garments, outfits, styleQueries), io, recentlyDeleted)
    val garmentDetail = DatabaseGarmentDetailSource(
        garments = garments,
        garmentWrites = garmentWrites,
        imageDirectory = photoPrefix,
        deletePhoto = photos::delete,
        removeBackground = ::removeBackground,
        io = io,
        recentlyDeleted = recentlyDeleted,
    )
    val bulkAdd = DatabaseBulkAddSource(garmentWrites, photos::delete, io)
    val garmentForm = DatabaseGarmentFormSource(garments, garmentWrites, duplicates, photos::delete, io)

    /** Sync's view of the same database; see SyncServer. */
    val sync = SyncStore(database)

    val importer: GarmentImporter = importer ?: run {
        val http = ImportHttp()
        FetchingGarmentImporter(http::pages, DownloadedPhotos(http, photos, photoPrefix), io)
    }

    /** Whether this server can cut a photo out at all; the browser offers it only if so. */
    val removesBackgrounds: Boolean get() = backgrounds != null

    /**
     * Cut the stored photo [photo] -- any form of its reference -- out of its
     * background, and store the cut-out under [id] the way the phone names
     * one; the cut-out's name. The original is not touched here: whether it
     * is kept beside the cut-out is the screen's to decide, as on the phone.
     */
    private fun removeBackground(photo: String, id: String): String {
        val remover = backgrounds ?: throw BackgroundRemovalUnavailable()
        val original = photos.file(toStoredImageRef(photo)) ?: throw PhotoNotFound()
        val name = cutoutFilename(id)
        photos.storeAs(name, remover.cutOut(original.readBytes()))
        return name
    }

    /** A reference to the stored photo [name], as this wardrobe's screens read references. */
    fun photoRef(name: String): String = resolveImageRef(name, photoPrefix)

    suspend fun storage(): StorageFigures = withContext(io) {
        StorageFigures(
            garments = garments.availableCount(),
            retired = garments.unavailableCount(),
            photoBytes = storedImageBytes(photos.directory),
        )
    }

    /**
     * Let go of the database file. The next question opens it again, which only
     * a test would ask; the server closes this when it stops.
     */
    override fun close() {
        style?.close()
        database.whileClosed { }
    }

    /**
     * Close the database for good, before its files are deleted: a request
     * still holding this wardrobe gets an error rather than a new, empty
     * database where the deleted one was. See ProfileRegistry.delete.
     */
    fun retire() = database.retire()

    companion object {
        private const val SYNC_CODE = "sync-code"

        /**
         * The pairing code of the wardrobe in [dataDirectory]. Its own
         * function so the registry can check a code against a profile
         * without opening that profile's wardrobe -- the database, the HTTP
         * client for imports -- which checking a code does not need.
         */
        fun syncSecretIn(dataDirectory: File) = SyncSecret(File(dataDirectory, SYNC_CODE))

        /**
         * Every file a wardrobe in [dataDirectory] keeps, and nothing else
         * there: what deleting the first profile deletes, whose directory is
         * the data directory itself and holds the list of profiles too.
         * The pairing code's half-written copy included, should a crash have
         * left one.
         */
        fun filesIn(dataDirectory: File): List<File> {
            val files = wardrobeFilesIn(dataDirectory)
            return listOf(
                files.databaseFile.parentFile,
                files.imagesDir,
                File(dataDirectory, SYNC_CODE),
                File(dataDirectory, "$SYNC_CODE.part"),
            )
        }
    }
}

/**
 * A product page's photo, downloaded into the wardrobe the way an upload is
 * stored: checked to be an image by its bytes, and kept under a name of the
 * server's choosing. :net does the request, inside its address checks and its
 * size limit, as on the phone.
 */
private class DownloadedPhotos(
    private val http: ImportHttp,
    private val photos: PhotoFiles,
    private val photoPrefix: String,
) : ImageFetcher {
    override fun download(url: String): String {
        val temporary = File.createTempFile("import-", null)
        return try {
            http.download(url, temporary)
            resolveImageRef(photos.store(temporary.readBytes()), photoPrefix)
        } finally {
            temporary.delete()
        }
    }
}
