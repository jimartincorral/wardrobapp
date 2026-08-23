package com.wardrobapp.app

import android.content.Context
import com.wardrobapp.data.AnalyticsQueries
import com.wardrobapp.data.ArchiveRestore
import com.wardrobapp.data.Duplicates
import com.wardrobapp.data.GARMENT_IMAGE_DIRNAME
import com.wardrobapp.data.GarmentQueries
import com.wardrobapp.data.GarmentWrites
import com.wardrobapp.data.OutfitQueries
import com.wardrobapp.data.OutfitWrites
import com.wardrobapp.data.ReopeningDriver
import com.wardrobapp.data.Suggestions
import com.wardrobapp.data.WardrobeFiles
import com.wardrobapp.data.WardrobeSchema
import java.io.File
import java.io.InputStream

/**
 * Everything the screens need, built once.
 *
 * Deliberately plain: one object, constructed on first use, handed down. A
 * dependency-injection framework would be more machinery than four query
 * classes and a driver warrant.
 */
class AppContainer(context: Context) {

    /**
     * The connection, reopened on demand.
     *
     * Through [ReopeningDriver] rather than opened once, because a restore
     * replaces the file this is holding. The schema is applied on every open,
     * exactly as the TypeScript client does -- including the open that follows a
     * restore, which may have installed a database written by an older build.
     */
    private val database = ReopeningDriver {
        AndroidSqlDriver.open(context).also { WardrobeSchema.applyTo(it) }
    }

    /**
     * Where garment photos live.
     *
     * The database stores bare filenames, so this is re-attached on read. The
     * `file://` prefix and trailing separator match what the React Native app
     * produced, since `resolveImageRef` concatenates directly onto it -- and
     * Coil loads a file:// URI directly.
     */
    val imageDirectory: String =
        "file://${File(context.filesDir, GARMENT_IMAGE_DIRNAME).absolutePath}/"

    val garments = GarmentQueries(database, imageDirectory)
    val garmentWrites = GarmentWrites(database)
    val outfits = OutfitQueries(database)
    val outfitWrites = OutfitWrites(database)
    val analytics = AnalyticsQueries(database)
    val suggestions = Suggestions(garments, outfits)
    val duplicates = Duplicates(garments)

    /** Where photos are decoded, scaled and written. */
    val photos = AndroidPhotoStore(context)

    private val restore = ArchiveRestore(
        files = WardrobeFiles(
            databaseFile = context.getDatabasePath(AndroidSqlDriver.DATABASE_NAME),
            imagesDir = File(context.filesDir, GARMENT_IMAGE_DIRNAME),
        ),
        // The same volume as the wardrobe, so installing the extracted archive
        // is a rename rather than a second copy of every photo.
        workRoot = context.cacheDir,
        databaseCheck = AndroidStagedDatabaseCheck(context),
    )

    /**
     * Replace the wardrobe with the contents of a backup archive.
     *
     * The connection is closed for the duration: the file it is holding is the
     * file being replaced. Throws [com.wardrobapp.data.UnrestorableArchiveException]
     * with something worth showing the user if the archive cannot be restored --
     * and in that case nothing has changed.
     */
    fun restoreFrom(archive: InputStream) {
        database.whileClosed { restore.restoreFromZip(archive) }
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
