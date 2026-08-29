package com.wardrobapp.app

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * The weekly backup: whether it is on, when it last ran, and how that went.
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

    /** Whether a weekly backup is meant to be running. */
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

    /**
     * Turn the weekly backup on, and queue it.
     *
     * `KEEP` rather than `UPDATE`: opening Settings should not push the next run a
     * week further out every time, which is what replacing the request does.
     */
    fun enable() {
        preferences.edit { putBoolean(KEY_ENABLED, true) }

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ScheduledBackupWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        // Unmetered, because this sends the whole wardrobe --
                        // photos included -- and doing that weekly over somebody's
                        // data plan without asking is not a decision to make for
                        // them. It is why the switch says so on screen.
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        // A backup is never so urgent that it should be the reason
                        // a phone dies before its owner gets home.
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build(),
        )
    }

    /**
     * Turn it off, and cancel what is queued.
     *
     * Also called when the Google account is disconnected: a schedule with no
     * permission left is a job that wakes every week, fails to get a token, and
     * retries forever without anybody being told.
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
        const val KEY_LAST_RUN = "last_run_at"
        const val KEY_LAST_FAILURE = "last_failure"

        /** So an exception that said nothing does not read as no exception. */
        const val UNSTATED_FAILURE = "?"

        const val WORK_NAME = "wardrobapp-weekly-drive-backup"
    }
}
