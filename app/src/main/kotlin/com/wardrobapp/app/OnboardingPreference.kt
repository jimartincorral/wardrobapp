package com.wardrobapp.app

import android.content.Context
import androidx.core.content.edit

/**
 * What a first launch has already been through.
 *
 * SharedPreferences for the same three reasons [ThemePreference] sets out, and one
 * of them is the reason this exists at all: whether the flow has run decides the
 * navigation graph's start destination, so it has to be known *before* the first
 * composition. A database read on a background dispatcher would show Home for a
 * frame and then replace it with a welcome screen.
 *
 * Its own file rather than `wardrobapp_appearance`, and deliberately outside
 * [AppSettings]'s backup allowlist. Two reasons, one of each kind:
 *
 *  - It is not a setting. Nothing here is a choice somebody made about how the app
 *    behaves; it is a record of what they have already seen, and putting it beside
 *    the theme would make "restore my settings" mean "and re-run my onboarding",
 *    or not, depending on which way the flag happened to fall.
 *  - Restoring a backup answers all three questions itself. A restore marks the
 *    flow seen and the card dismissed on the spot -- a wardrobe that arrived whole
 *    has no first steps to take -- so there is nothing for an archive to carry.
 */
class OnboardingPreference(context: Context) {

    private val preferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Whether the three-screen flow has run.
     *
     * Set by finishing the last screen, by "Not now", and by a successful restore.
     * Never unset: there is no way back into the flow, by design -- it says what
     * the app is, and the app is on screen by then.
     */
    var seen: Boolean
        get() = preferences.getBoolean(KEY_SEEN, false)
        set(value) = preferences.edit { putBoolean(KEY_SEEN, value) }

    /** Whether the First steps card has been sent away for good. */
    var firstStepsDismissed: Boolean
        get() = preferences.getBoolean(KEY_FIRST_STEPS_DISMISSED, false)
        set(value) = preferences.edit { putBoolean(KEY_FIRST_STEPS_DISMISSED, value) }

    /**
     * Whether a bulk-add session has ever produced a drawerful.
     *
     * The one flag with nothing behind it in the wardrobe: garments added in bulk
     * are ordinary garment rows, and nothing on them records how they arrived. So
     * unlike the other two rows on the card, this one has to be remembered rather
     * than counted.
     */
    var bulkAddUsed: Boolean
        get() = preferences.getBoolean(KEY_BULK_ADD_USED, false)
        set(value) = preferences.edit { putBoolean(KEY_BULK_ADD_USED, value) }

    /**
     * Both card flags at once.
     *
     * Read together rather than one at a time so a screen holds one snapshot: the
     * card's rows and its own presence are decided from the same pair, and reading
     * them at two moments is how a dismissed card comes back with a row ticked.
     */
    fun firstStepFlags(): FirstStepFlags = FirstStepFlags(
        dismissed = firstStepsDismissed,
        bulkAddUsed = bulkAddUsed,
    )

    private companion object {
        const val FILE_NAME = "wardrobapp_onboarding"
        const val KEY_SEEN = "onboarding_seen"
        const val KEY_FIRST_STEPS_DISMISSED = "first_steps_dismissed"
        const val KEY_BULK_ADD_USED = "bulk_add_used"
    }
}

/** The stored half of what the First steps card shows. The rest is counted. */
data class FirstStepFlags(val dismissed: Boolean, val bulkAddUsed: Boolean)
