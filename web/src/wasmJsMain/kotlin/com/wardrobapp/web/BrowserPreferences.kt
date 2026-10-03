package com.wardrobapp.web

import com.wardrobapp.presentation.GarmentCaption
import com.wardrobapp.presentation.ThemeChoice
import com.wardrobapp.presentation.WardrobeView
import com.wardrobapp.presentation.WardrobeViewSettings
import com.wardrobapp.presentation.garmentCaptionFor
import com.wardrobapp.presentation.storedValue
import com.wardrobapp.presentation.themeChoiceFor
import com.wardrobapp.presentation.wardrobeViewFor
import kotlinx.browser.localStorage

/*
 * What the phone keeps in SharedPreferences, kept in the browser's local storage.
 *
 * Per browser rather than on the server, deliberately: these are how one person
 * likes to look at the wardrobe -- dark or light, a grid or a list -- and two
 * people sharing a wardrobe through Home Assistant need not agree about them. The
 * stored values are the phone's, through the same functions in :presentation,
 * so an unrecognised one reads as the default here exactly as it does there.
 *
 * Every read and write tolerates storage that is not there: a private window,
 * storage blocked by the browser, a quota. The preference is then simply not
 * remembered, which is the honest outcome for a setting nobody can keep.
 */

private fun read(key: String): String? = try {
    localStorage.getItem(key)
} catch (_: Throwable) {
    null
}

private fun write(key: String, value: String?) {
    try {
        if (value == null) localStorage.removeItem(key) else localStorage.setItem(key, value)
    } catch (_: Throwable) {
        // Not remembered; see above.
    }
}

object ThemePreference {
    var choice: ThemeChoice
        get() = themeChoiceFor(read("wardrobapp.theme"))
        set(value) = write("wardrobapp.theme", value.storedValue)
}

class BrowserWardrobeView : WardrobeViewSettings {
    override var view: WardrobeView
        get() = wardrobeViewFor(read("wardrobapp.layout"), read("wardrobapp.columns")?.toIntOrNull())
        set(value) {
            write("wardrobapp.layout", value.layout.storedValue)
            write("wardrobapp.columns", value.columns.toString())
        }

    override var caption: GarmentCaption
        get() = garmentCaptionFor(read("wardrobapp.caption"))
        set(value) = write("wardrobapp.caption", value.storedValue)
}

/**
 * The version of the Home Assistant app this browser last showed What's new
 * for, or decided there was nothing to show for: WhatsNewRecord's counterpart.
 * Per browser, like everything here, so each person sees it once wherever
 * they open the app -- and not at all on a browser that has never opened it.
 */
object WhatsNewSeen {
    var version: String?
        get() = read("wardrobapp.whatsNewSeen")
        set(value) = write("wardrobapp.whatsNewSeen", value)
}

/**
 * The profile this browser last opened, for a server that cannot say who is
 * asking (see profileToOpen). Under Home Assistant the person's own profile
 * wins over this, so a shared tablet opens each person's own.
 */
object LastProfile {
    var id: String?
        get() = read("wardrobapp.profile")
        set(value) = write("wardrobapp.profile", value)
}

/** The first-steps card's two stored answers, as OnboardingPreference keeps them on the phone. */
object FirstStepFlags {
    var dismissed: Boolean
        get() = read("wardrobapp.firstStepsDismissed") == "true"
        set(value) = write("wardrobapp.firstStepsDismissed", value.toString())

    var bulkAddUsed: Boolean
        get() = read("wardrobapp.bulkAddUsed") == "true"
        set(value) = write("wardrobapp.bulkAddUsed", value.toString())
}
