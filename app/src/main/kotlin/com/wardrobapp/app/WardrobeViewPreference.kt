package com.wardrobapp.app

import android.content.Context
import androidx.core.content.edit
import com.wardrobapp.presentation.WardrobeView
import com.wardrobapp.presentation.storedValue
import com.wardrobapp.presentation.wardrobeViewFor

/**
 * Where the wardrobe's layout is kept.
 *
 * Beside the theme, in the same preferences file and for the same three reasons
 * [ThemePreference] sets out: a restore replaces the wardrobe and how you like
 * looking at it is not part of one; it is wanted before the list is first drawn;
 * and `user_preferences` in the database holds migration flags rather than
 * settings. A grid that arrived one frame after a list would be a visible jump.
 *
 * Two keys rather than one string to parse, because the two halves are a word and
 * a number and SharedPreferences can hold both. [wardrobeViewFor] decides what an
 * unrecognised pair means.
 */
class WardrobeViewPreference(context: Context) {

    private val preferences =
        context.getSharedPreferences(APPEARANCE_PREFERENCES, Context.MODE_PRIVATE)

    /** The view in force. Read every time, as the theme is, so there is one copy. */
    var view: WardrobeView
        get() = wardrobeViewFor(
            preferences.getString(KEY_LAYOUT, null),
            // Absent is null rather than 0: a count of zero is not a count, and
            // `wardrobeViewFor` would otherwise snap it to the narrowest grid.
            preferences.getInt(KEY_COLUMNS, NO_COLUMNS).takeIf { it != NO_COLUMNS },
        )
        set(value) {
            preferences.edit {
                // The list is stored as the absence of a layout, so an install that
                // has never chosen and one that chose the list read alike.
                val layout = value.layout.storedValue
                if (layout == null) remove(KEY_LAYOUT) else putString(KEY_LAYOUT, layout)

                // The width is kept even while the layout is a list, because that
                // is what returning to a grid returns to.
                putInt(KEY_COLUMNS, value.columns)
            }
        }

    private companion object {
        const val KEY_LAYOUT = "wardrobe_layout"
        const val KEY_COLUMNS = "wardrobe_columns"

        /** Not a possible count, so it can mean "nothing stored". */
        const val NO_COLUMNS = 0
    }
}
