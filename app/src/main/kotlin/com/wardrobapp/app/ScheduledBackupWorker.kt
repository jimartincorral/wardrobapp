package com.wardrobapp.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import net.openid.appauth.AuthorizationService

/**
 * The scheduled backup, running with nobody watching.
 *
 * Which is the whole reason the sign-in asked for `access_type=offline`: without a
 * refresh token this could only work while somebody was looking at the screen, and
 * a backup that needs an audience is not a backup.
 *
 * It does exactly what the button does, through the same [DriveBackupRunner],
 * because a schedule that backed up slightly differently would be a second thing to
 * get right and the half nobody sees is the half that would drift.
 */
class ScheduledBackupWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val schedule = BackupSchedule(app)

        // Switched off between this being queued and it running. Success rather
        // than failure: nothing went wrong, there is simply nothing to do, and a
        // failure would earn a retry it does not want.
        if (!schedule.enabled) return Result.success()

        val auth = DriveAuth(app)

        // The permission is gone -- revoked from the Google account, or the app's
        // data cleared. Retrying weekly forever would be a job that can never
        // succeed, so it takes itself off instead. The screen then shows the switch
        // off, which is the truth.
        if (!auth.isSignedIn) {
            schedule.disable()
            return Result.success()
        }

        val service = AuthorizationService(app)

        return try {
            val drive = AndroidDriveBackups(app) { auth.accessToken(service) }
            DriveBackupRunner(app, AppContainer.get(app), drive).backUp(schedule.retention)

            schedule.recordSuccess(System.currentTimeMillis())
            Result.success()
        } catch (error: Exception) {
            schedule.recordFailure(System.currentTimeMillis(), error.message)

            // A few goes with the backoff WorkManager applies, then stop until next
            // interval. Retrying indefinitely turns one bad run into a job that
            // never stops trying, and the schedule is itself the outer retry.
            if (runAttemptCount + 1 < ATTEMPTS) Result.retry() else Result.failure()
        } finally {
            // Holds a browser binding. Leaking one leaks the connection behind it,
            // and this runs unattended often enough for that to accumulate.
            service.dispose()
        }
    }

    private companion object {
        const val ATTEMPTS = 3
    }
}
