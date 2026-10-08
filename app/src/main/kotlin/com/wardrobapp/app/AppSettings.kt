package com.wardrobapp.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import com.wardrobapp.data.ArchiveSettings
import com.wardrobapp.data.SettingValue

/**
 * Reading how this phone is set up, and writing it back.
 *
 * The archive format is :data's; what belongs in it is decided here, because this
 * is the layer that knows what each preference file holds.
 *
 * **The list is an allowlist, and that is the security decision in this file.**
 * A preference file added later is not backed up until somebody puts it here on
 * purpose. The two failure modes are not comparable: forgetting to add a file
 * means a setting does not travel, and somebody adjusts it once. Forgetting to
 * *exclude* one means whatever it holds is written into a zip that gets uploaded
 * to Drive, downloaded, copied about and shared -- and `wardrobapp_drive` holds an
 * OAuth refresh token for a Google account. A credential does not go in a backup,
 * and the way to be sure is that nothing goes in unless it was named.
 */
class AppSettings(context: Context) {

    private val context = context.applicationContext

    /** What this phone would put in a backup. */
    fun capture(): ArchiveSettings = ArchiveSettings(
        // Android stores the app's language itself rather than in a preference
        // file, so it is read from where it actually lives. Empty means "follow
        // the system", which is a choice worth carrying as much as a named one --
        // but it is recorded as null, since restoring "" and restoring nothing
        // would otherwise be two spellings of the same thing.
        language = AppCompatDelegate.getApplicationLocales().toLanguageTags().ifBlank { null },
        preferences = BACKED_UP.associateWith { file ->
            context.getSharedPreferences(file, Context.MODE_PRIVATE)
                .all
                .mapNotNull { (key, value) -> value.asSettingValue()?.let { key to it } }
                .toMap()
        },
    )

    /**
     * Put a backup's settings into effect.
     *
     * Only the files on the allowlist, and only from a set already filtered on the
     * way in -- an archive is a file somebody could have edited, so a name in it is
     * not permission to write to that name.
     *
     * A file present in the archive replaces what is here rather than merging into
     * it, so a setting turned on before the backup and off after restores as off.
     * Merging would leave a state that was never anybody's.
     */
    fun apply(settings: ArchiveSettings) {
        for ((file, values) in settings.preferences) {
            if (file !in BACKED_UP) continue

            context.getSharedPreferences(file, Context.MODE_PRIVATE).edit {
                clear()
                for ((key, value) in values) {
                    when (value) {
                        is SettingValue.Text -> putString(key, value.value)
                        is SettingValue.Flag -> putBoolean(key, value.value)
                        is SettingValue.Whole -> putInt(key, value.value)
                        is SettingValue.Big -> putLong(key, value.value)
                    }
                }
            }
        }

        // The schedule's file was just overwritten with another phone's; make
        // what is queued agree with it. See BackupSchedule.restored.
        if ("wardrobapp_backup_schedule" in settings.preferences) BackupSchedule(context).restored()

        // Last, because it is the one that restarts activities to take effect.
        //
        // Posted to the main thread rather than called here, because this runs
        // on IO, after the restore, and on Android 12 and below AppCompat
        // applies a locale by recreating every activity then and there, on the
        // calling thread -- which Activity.recreate refuses off the main one.
        // The wardrobe was restored and the settings written by then; what the
        // exception cost was the language, and a raw Java message in place of
        // "restored". Android 13 and up hand the locale to the system instead
        // and never had the problem.
        settings.language?.let {
            Handler(Looper.getMainLooper()).post {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it))
            }
        }
    }

    private fun Any?.asSettingValue(): SettingValue? = when (this) {
        is String -> SettingValue.Text(this)
        is Boolean -> SettingValue.Flag(this)
        is Int -> SettingValue.Whole(this)
        is Long -> SettingValue.Big(this)
        // Floats and string sets, neither of which this app stores. Dropped
        // rather than coerced: a setting that came back as something else would
        // be worse than one that did not come back.
        else -> null
    }

    private companion object {
        /**
         * Every preference file a backup carries. Named one at a time, on purpose.
         *
         * Conspicuously absent: `wardrobapp_drive`. See the note on this class --
         * that file is the Google credential, and it is the reason this is a list
         * of what to include rather than a list of what to skip. And
         * `wardrobapp_sync`, which holds the code that pairs this phone with Home
         * Assistant, a credential to the wardrobe on the server.
         */
        val BACKED_UP = listOf(
            // Theme, and how the wardrobe is drawn.
            APPEARANCE_PREFERENCES,
            // Whether the scheduled backup is on, how often, how many to keep, and
            // the Wi-Fi and battery rules. The run history rides along and is
            // dropped again on restore, and the queued job is made to agree with
            // the flag -- see BackupSchedule.restored, and why a flag alone is
            // not enough.
            "wardrobapp_backup_schedule",
            // Which update was skipped.
            "wardrobapp_updates",
        )
    }
}
