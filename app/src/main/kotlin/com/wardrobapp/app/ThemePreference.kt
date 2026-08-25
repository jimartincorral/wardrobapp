package com.wardrobapp.app

import android.content.Context
import androidx.core.content.edit
import com.wardrobapp.presentation.ThemeChoice
import com.wardrobapp.presentation.storedValue
import com.wardrobapp.presentation.themeChoiceFor

/**
 * The file both appearance settings share.
 *
 * Named here because [ThemePreference] documents why it is not the default
 * `<package>_preferences` file and not the database, and that reasoning is the
 * same for anything else about how the app is drawn.
 */
internal const val APPEARANCE_PREFERENCES = "wardrobapp_appearance"

/**
 * Where the theme choice is kept.
 *
 * SharedPreferences, and not the `user_preferences` table the schema carries, for
 * three reasons worth writing down because the table looks like the obvious home:
 *
 *  - A restore replaces the wardrobe, and a theme is not part of one. Putting the
 *    choice in the database would mean restoring a backup from another phone
 *    repainted this one -- or, since the app this replaced writes nothing to that
 *    table, silently reset the choice to Automatic.
 *  - It has to be known *before* the first composition, or the app draws in the
 *    wrong colours and then corrects itself. The database is opened lazily on a
 *    background dispatcher; SharedPreferences is loaded for exactly this.
 *  - `user_preferences` is not a settings store in practice. In the React Native
 *    app it holds run-once migration flags (`src/db/migrations.ts`) and nothing
 *    else -- that app keeps this very setting in AsyncStorage, which is to say
 *    outside the database too.
 *
 * It also puts the theme where the language already is: AppCompat persists the
 * locale in its own preferences file, so both settings survive a restart the same
 * way and neither travels in a backup.
 */
class ThemePreference(context: Context) {

    private val preferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * The choice in force.
     *
     * Read every time rather than cached: the only writer is the setter below, but
     * a field would be one more copy of a setting to keep in step -- the mistake
     * the language picker is written to avoid.
     */
    var choice: ThemeChoice
        get() = themeChoiceFor(preferences.getString(KEY, null))
        set(value) {
            preferences.edit {
                // Removed rather than written as "system": following the device is
                // the absence of a choice, which is what `storedValue` returning
                // null says. It also means a preferences file from a future build
                // that stored something else is cleaned up by choosing Automatic.
                val stored = value.storedValue
                if (stored == null) remove(KEY) else putString(KEY, stored)
            }
        }

    private companion object {
        /**
         * Deliberately not the default `<package>_preferences` file, which belongs
         * to PreferenceManager and would put this next to anything a settings
         * screen ever generates.
         */
        const val FILE_NAME = APPEARANCE_PREFERENCES
        const val KEY = "theme_mode"
    }
}
