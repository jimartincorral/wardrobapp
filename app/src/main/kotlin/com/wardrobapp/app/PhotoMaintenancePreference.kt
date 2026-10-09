package com.wardrobapp.app

import android.content.Context
import androidx.core.content.edit

/**
 * What the photo directory has been through, so a pass that should run once
 * runs once.
 *
 * Separate from the tidy-up a person asks for in Settings, which can be run
 * as often as they like: this is for the passes an update owes the photos
 * already on the phone, run on its own the first time the new build starts.
 */
class PhotoMaintenancePreference(context: Context) {

    private val preferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Whether every cut-out stored before cut-outs were framed has been framed
     * since; see CutoutFraming in :data. Set once the pass has been through the
     * whole directory, not when it starts, so a pass the app was killed in
     * runs again next time.
     */
    var cutoutsFramed: Boolean
        get() = preferences.getBoolean(KEY_CUTOUTS_FRAMED, false)
        set(value) = preferences.edit { putBoolean(KEY_CUTOUTS_FRAMED, value) }

    private companion object {
        const val FILE_NAME = "photo_maintenance"
        const val KEY_CUTOUTS_FRAMED = "cutouts_framed"
    }
}
