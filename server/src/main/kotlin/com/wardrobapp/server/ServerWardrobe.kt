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
import com.wardrobapp.data.ReopeningDriver
import com.wardrobapp.data.Suggestions
import com.wardrobapp.data.SyncStore
import com.wardrobapp.data.WardrobeSchema
import com.wardrobapp.data.resolveImageRef
import com.wardrobapp.data.storedImageBytes
import com.wardrobapp.data.wardrobeFilesIn
import com.wardrobapp.domain.ImageFetcher
import com.wardrobapp.net.ImportHttp
import com.wardrobapp.presentation.DatabaseBulkAddSource
import com.wardrobapp.presentation.DatabaseGarmentDetailSource
import com.wardrobapp.presentation.DatabaseGarmentFormSource
import com.wardrobapp.presentation.DatabaseHomeSource
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
 * [importer] is for tests, which have no internet to import from.
 */
class ServerWardrobe(
    dataDirectory: File,
    importer: GarmentImporter? = null,
) : AutoCloseable {

    private val files = wardrobeFilesIn(dataDirectory)

    private val database = ReopeningDriver {
        JdbcSqlDriver.open(files.databaseFile).also { WardrobeSchema.applyTo(it) }
    }

    val photos = PhotoFiles(files.imagesDir)

    private val garments = GarmentQueries(database, Routes.PHOTO_FILES)
    private val garmentWrites = GarmentWrites(database)
    private val outfits = OutfitQueries(database)
    private val outfitWrites = OutfitWrites(database)
    private val duplicates = Duplicates(garments)

    private val io = Dispatchers.IO

    val home = DatabaseHomeSource(garments, outfits, io)
    val outfitDetail = DatabaseOutfitDetailSource(outfits, outfitWrites, garments, io)
    val outfitEdit = DatabaseOutfitEditSource(garments, outfits, outfitWrites, io)
    val wardrobe = DatabaseWardrobeSource(garments, io)
    val statistics = DatabaseStatisticsSource(
        garments = garments,
        analytics = AnalyticsQueries(database),
        duplicates = duplicates,
        gaps = Gaps(garments, outfits),
        imageDirectory = Routes.PHOTO_FILES,
        io = io,
    )
    val outfitList = DatabaseOutfitsSource(garments, outfits, outfitWrites, Suggestions(garments, outfits), io)
    val garmentDetail = DatabaseGarmentDetailSource(
        garments = garments,
        garmentWrites = garmentWrites,
        imageDirectory = Routes.PHOTO_FILES,
        deletePhoto = photos::delete,
        // See HttpGarmentDetailSource.cutOut: not on the server, yet. Nothing
        // routes here, so this only says why if something one day does.
        removeBackground = { _, _ -> throw UnsupportedOperationException("Removing a background is not available on the server yet.") },
        io = io,
    )
    val bulkAdd = DatabaseBulkAddSource(garmentWrites, photos::delete, io)
    val garmentForm = DatabaseGarmentFormSource(garments, garmentWrites, duplicates, photos::delete, io)

    /** Sync's view of the same database; see SyncServer. */
    val sync = SyncStore(database)

    /** The code a phone pairs with, kept beside the wardrobe it opens. */
    val syncSecret = SyncSecret(File(dataDirectory, "sync-code"))

    val importer: GarmentImporter = importer ?: run {
        val http = ImportHttp()
        FetchingGarmentImporter(http::pages, DownloadedPhotos(http, photos), io)
    }

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
        database.whileClosed { }
    }
}

/**
 * A product page's photo, downloaded into the wardrobe the way an upload is
 * stored: checked to be an image by its bytes, and kept under a name of the
 * server's choosing. :net does the request, inside its address checks and its
 * size limit, as on the phone.
 */
private class DownloadedPhotos(private val http: ImportHttp, private val photos: PhotoFiles) : ImageFetcher {
    override fun download(url: String): String {
        val temporary = File.createTempFile("import-", null)
        return try {
            http.download(url, temporary)
            resolveImageRef(photos.store(temporary.readBytes()), Routes.PHOTO_FILES)
        } finally {
            temporary.delete()
        }
    }
}
