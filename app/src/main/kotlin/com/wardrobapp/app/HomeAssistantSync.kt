package com.wardrobapp.app

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wardrobapp.api.BackgroundSync
import com.wardrobapp.api.SyncPreferences
import java.util.concurrent.TimeUnit

/*
 * Syncing with Home Assistant: the three parts of it that are Android's.
 *
 * Everything else -- pairing, which syncs may run, what a failure means, what
 * Settings shows -- is PhoneSync in :api, tested there against a real server.
 * What is left here is where its settings are kept and how it runs unattended.
 */

/**
 * The sync settings, in a preference file of their own.
 *
 * Of their own because the pairing code is a credential: whoever has it can
 * read and rewrite the wardrobe on the server. AppSettings' allowlist does not
 * name this file, so a backup never carries it, any more than it carries the
 * Drive token -- and nor do the address and the switches, which are about this
 * phone and this network rather than about the wardrobe. A wardrobe restored
 * onto another phone is paired again by whoever holds that phone.
 */
class SharedPreferencesSyncSettings(context: Context) : SyncPreferences {

    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override var address: String?
        get() = preferences.getString(KEY_ADDRESS, null)
        set(value) = preferences.edit { putString(KEY_ADDRESS, value) }

    override var code: String?
        get() = preferences.getString(KEY_CODE, null)
        set(value) = preferences.edit { putString(KEY_CODE, value) }

    override var lastSyncedAt: String?
        get() = preferences.getString(KEY_LAST_SYNCED, null)
        set(value) = preferences.edit { putString(KEY_LAST_SYNCED, value) }

    override var lastFailure: String?
        get() = preferences.getString(KEY_LAST_FAILURE, null)
        set(value) = preferences.edit { putString(KEY_LAST_FAILURE, value) }

    /**
     * On by default: somebody who pairs a phone asked for the two to stay in
     * step, and a phone that only syncs while it is open stays in step only as
     * often as it is opened.
     */
    override var background: Boolean
        get() = preferences.getBoolean(KEY_BACKGROUND, true)
        set(value) = preferences.edit { putBoolean(KEY_BACKGROUND, value) }

    /**
     * On by default, for the reason BackupSchedule's is: a sync can carry
     * photos, and doing that by itself over somebody's data plan is a bill they
     * did not agree to. Off is a real choice for somebody who reaches Home
     * Assistant from outside.
     */
    override var wifiOnly: Boolean
        get() = preferences.getBoolean(KEY_WIFI_ONLY, true)
        set(value) = preferences.edit { putBoolean(KEY_WIFI_ONLY, value) }

    private companion object {
        const val FILE_NAME = "wardrobapp_sync"
        const val KEY_ADDRESS = "address"
        const val KEY_CODE = "code"
        const val KEY_LAST_SYNCED = "last_synced_at"
        const val KEY_LAST_FAILURE = "last_failure"
        const val KEY_BACKGROUND = "background"
        const val KEY_WIFI_ONLY = "wifi_only"
    }
}

/**
 * The background sync, as WorkManager work.
 *
 * Every three hours, which is a guess at the right trade and says so: often
 * enough that what was changed in the browser over an afternoon is on the
 * phone by the evening, rarely enough that a phone at home all day wakes for
 * this eight times rather than a hundred. Opening the app syncs as well, so
 * the interval only decides how stale a phone that nobody opens can get.
 *
 * `UPDATE` rather than `KEEP`: this is only called when a rule changed or the
 * phone was paired, and in both cases the new rule is the one that should
 * apply, without pushing the next run three hours further out.
 */
class WorkManagerBackgroundSync(context: Context) : BackgroundSync {

    private val context = context.applicationContext

    override fun schedule(wifiOnly: Boolean) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<BackgroundSyncWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build(),
        )
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private companion object {
        const val WORK_NAME = "wardrobapp-home-assistant-sync"
        const val INTERVAL_HOURS = 3L
    }
}

/**
 * One background sync, through the same PhoneSync as the button in Settings,
 * so the two cannot run at once and Settings shows what this did.
 *
 * Always a success to WorkManager, failures included. The usual failure is the
 * phone being away from home, and retrying on WorkManager's backoff would only
 * fail again until it is back -- the next interval is the retry. The failure
 * itself is recorded where Settings shows it.
 */
class BackgroundSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        AppContainer.get(applicationContext).sync.syncInBackground()
        return Result.success()
    }
}
