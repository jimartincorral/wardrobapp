package com.wardrobapp.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.wardrobapp.presentation.BackupFrequency
import com.wardrobapp.presentation.BackupRetention
import com.wardrobapp.presentation.backupFrequencyFor
import com.wardrobapp.presentation.backupRetentionFor
import com.wardrobapp.presentation.days
import com.wardrobapp.presentation.storedValue
import java.util.concurrent.TimeUnit

/**
 * The scheduled backup: whether it is on, how it is set up, and how the last run
 * went.
 *
 * One class rather than a preference and a scheduler, because the two must not
 * disagree. A stored flag saying "on" with nothing queued is a safety net that
 * quietly is not there, and work queued with the flag off is a job that runs after
 * somebody switched it off. Turning it on and off goes through here so both move
 * together.
 *
 * SharedPreferences for the same reasons the theme uses it, written down in
 * [ThemePreference]: this is a setting about the phone rather than about the
 * wardrobe, and a restore from another device must not bring it along -- a backup
 * schedule that arrived inside a backup would be somebody else's decision applied
 * to this phone's data plan.
 */
class BackupSchedule(context: Context) {

    private val context = context.applicationContext

    private val preferences =
        this.context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Whether a scheduled backup is meant to be running. */
    val enabled: Boolean get() = preferences.getBoolean(KEY_ENABLED, false)

    /** When a backup last finished, successfully or not. Null until one has. */
    val lastRunAt: Long? get() = preferences.getLong(KEY_LAST_RUN, 0L).takeIf { it > 0L }

    /**
     * Why the last run failed, or null when it succeeded.
     *
     * Kept because an unattended job that stops working has nobody watching it: the
     * only place its failure can surface is the screen somebody visits later, and
     * "nothing has happened for a month" is not a message.
     */
    val lastFailure: String? get() = preferences.getString(KEY_LAST_FAILURE, null)

    /** How often it runs. */
    var frequency: BackupFrequency
        get() = backupFrequencyFor(preferences.getString(KEY_FREQUENCY, null))
        set(value) = changing { putString(KEY_FREQUENCY, value.storedValue) }

    /** How many archives the folder keeps. */
    var retention: BackupRetention
        get() = backupRetentionFor(preferences.getString(KEY_RETENTION, null))
        set(value) = changing { putString(KEY_RETENTION, value.storedValue) }

    /**
     * Whether to wait for Wi-Fi.
     *
     * On by default, because a run sends the whole wardrobe -- photos included --
     * and doing that on a repeating schedule over somebody's data plan is a bill
     * they did not agree to. Off is a real choice for an unlimited plan, and the
     * switch is where it is made.
     */
    var wifiOnly: Boolean
        get() = preferences.getBoolean(KEY_WIFI_ONLY, true)
        set(value) = changing { putBoolean(KEY_WIFI_ONLY, value) }

    /** Whether to wait until the battery is not low. */
    var batteryNotLow: Boolean
        get() = preferences.getBoolean(KEY_BATTERY_NOT_LOW, true)
        set(value) = changing { putBoolean(KEY_BATTERY_NOT_LOW, value) }

    /**
     * Turn the backup on, and queue it.
     *
     * `KEEP` rather than `UPDATE`, and the difference is the whole reason there are
     * two methods: opening Settings and switching this on again should not push the
     * next run a week further out, which is what replacing a request does. A
     * *changed* setting is the opposite case and goes through [changing].
     */
    fun enable() {
        preferences.edit { putBoolean(KEY_ENABLED, true) }
        queue(ExistingPeriodicWorkPolicy.KEEP)
    }

    /**
     * Store a changed setting, and make it take effect.
     *
     * `UPDATE`, because a frequency or a constraint that was only written down
     * would leave the job running to the old rule -- the setting would look changed
     * and behave as it did before, which is the worst of both.
     *
     * Nothing is queued while the schedule is off. Switching it on later reads
     * whatever is stored, so a setting changed first and enabled afterwards is
     * still the setting that applies.
     */
    private fun changing(edit: SharedPreferences.Editor.() -> Unit) {
        preferences.edit { edit() }
        if (enabled) queue(ExistingPeriodicWorkPolicy.UPDATE)
    }

    private fun queue(policy: ExistingPeriodicWorkPolicy) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            policy,
            PeriodicWorkRequestBuilder<ScheduledBackupWorker>(frequency.days, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                        )
                        .setRequiresBatteryNotLow(batteryNotLow)
                        .build(),
                )
                .build(),
        )
    }

    /**
     * Turn it off, and cancel what is queued.
     *
     * Also called when the Google account is disconnected: a schedule with no
     * permission left is a job that wakes on its interval, fails to get a token,
     * and retries forever without anybody being told.
     */
    fun disable() {
        preferences.edit { putBoolean(KEY_ENABLED, false) }
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun recordSuccess(atMillis: Long) = preferences.edit {
        putLong(KEY_LAST_RUN, atMillis)
        remove(KEY_LAST_FAILURE)
    }

    fun recordFailure(atMillis: Long, message: String?) = preferences.edit {
        putLong(KEY_LAST_RUN, atMillis)
        // A failure with no message still has to read as a failure rather than as a
        // success, so the key is always present when one happened.
        putString(KEY_LAST_FAILURE, message ?: UNSTATED_FAILURE)
    }

    private companion object {
        const val FILE_NAME = "wardrobapp_backup_schedule"
        const val KEY_ENABLED = "weekly_enabled"
        const val KEY_FREQUENCY = "frequency"
        const val KEY_RETENTION = "retention"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_BATTERY_NOT_LOW = "battery_not_low"
        const val KEY_LAST_RUN = "last_run_at"
        const val KEY_LAST_FAILURE = "last_failure"

        /** So an exception that said nothing does not read as no exception. */
        const val UNSTATED_FAILURE = "?"

        const val WORK_NAME = "wardrobapp-weekly-drive-backup"
    }
}
