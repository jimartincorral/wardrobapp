package com.wardrobapp.app

import android.content.Context
import com.wardrobapp.data.AnalyticsQueries
import com.wardrobapp.data.GARMENT_IMAGE_DIRNAME
import com.wardrobapp.data.GarmentQueries
import com.wardrobapp.data.GarmentWrites
import com.wardrobapp.data.OutfitQueries
import com.wardrobapp.data.OutfitWrites
import com.wardrobapp.data.WardrobeSchema
import java.io.File

/**
 * Everything the screens need, built once.
 *
 * Deliberately plain: one object, constructed on first use, handed down. A
 * dependency-injection framework would be more machinery than four query
 * classes and a driver warrant.
 */
class AppContainer(context: Context) {

    private val driver = AndroidSqlDriver.open(context).also { WardrobeSchema.applyTo(it) }

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

    val garments = GarmentQueries(driver, imageDirectory)
    val garmentWrites = GarmentWrites(driver)
    val outfits = OutfitQueries(driver)
    val outfitWrites = OutfitWrites(driver)
    val analytics = AnalyticsQueries(driver)

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
