package com.wardrobapp.app

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.core.net.toUri
import com.wardrobapp.data.DRIVE_SCOPE
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues

/**
 * Permission to reach one folder of somebody's Google Drive, and keeping it.
 *
 * The one place in this app that holds a credential to somebody's account, which
 * is why it is the one place not written by hand. The update checker does its own
 * HTTP because the worst a mistake there costs is a failed download; here a
 * mistake costs a token, so the authorization request, PKCE, the exchange and the
 * refresh are AppAuth's.
 *
 * What this app ever gets is `drive.file`: files it created itself, and nothing
 * else in the Drive. Somebody can look at the folder, download the archives and
 * delete them without this app being involved, and this app cannot see anything
 * they did not make with it.
 */
class DriveAuth(context: Context) {

    private val context = context.applicationContext

    private val preferences =
        this.context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Whether there is a usable authorization, refreshable or not yet spent. */
    val isSignedIn: Boolean get() = readState().isAuthorized

    /**
     * The screen that asks.
     *
     * A Custom Tab onto Google's own sign-in, not a WebView: a password typed into
     * a WebView is typed into this app's process, and an app that could read it
     * should not be trusted not to. The tab shares the browser's session, so
     * somebody already signed in on this phone is choosing an account rather than
     * signing in again.
     */
    fun authorizationIntent(service: AuthorizationService): Intent =
        service.getAuthorizationRequestIntent(request())

    /**
     * Take the answer, and turn the code into tokens.
     *
     * Two round trips look like one to the caller: AppAuth hands back an
     * authorization code, and the token exchange that follows is the request that
     * actually produces something worth keeping.
     */
    suspend fun completeAuthorization(data: Intent, service: AuthorizationService) {
        val response = AuthorizationResponse.fromIntent(data)
        val failure = AuthorizationException.fromIntent(data)

        if (response == null) {
            throw failure
                ?: IOException(context.getString(R.string.error_drive_signin_incomplete))
        }

        val state = readState().apply { update(response, failure) }

        val tokens = suspendCancellableCoroutine { continuation ->
            service.performTokenRequest(response.createTokenExchangeRequest()) { result, error ->
                when {
                    result != null -> continuation.resume(result)
                    else -> continuation.resumeWithException(
                        error ?: IOException(context.getString(R.string.error_drive_no_permission)),
                    )
                }
            }
        }

        state.update(tokens, null)
        writeState(state)
    }

    /**
     * A token good for the next request, refreshing it first if it is not.
     *
     * The refresh is AppAuth's decision rather than this app's: it holds the
     * expiry, and asking it means one implementation of "is this still good"
     * instead of two that can disagree. The state is written back afterwards
     * because a refresh produces a new access token, and losing it means
     * refreshing again on every single request.
     */
    suspend fun accessToken(service: AuthorizationService): String {
        val state = readState()

        val token = suspendCancellableCoroutine { continuation ->
            state.performActionWithFreshTokens(service) { accessToken, _, error ->
                when {
                    accessToken != null -> continuation.resume(accessToken)
                    else -> continuation.resumeWithException(
                        error ?: IOException(context.getString(R.string.error_drive_no_access)),
                    )
                }
            }
        }

        writeState(state)
        return token
    }

    /**
     * Forget the authorization.
     *
     * Local only, and says so where it is offered: this drops the token, it does
     * not reach into the account and revoke it, and it does not touch a single
     * file in the Drive. The backups stay where they are, which is the point of
     * putting them somewhere their owner can see.
     */
    fun signOut() = preferences.edit { remove(KEY) }

    private fun request(): AuthorizationRequest =
        AuthorizationRequest.Builder(
            GOOGLE,
            BuildConfig.DRIVE_CLIENT_ID,
            ResponseTypeValues.CODE,
            "${BuildConfig.APPLICATION_ID}:/oauth2redirect".toUri(),
        )
            .setScope(DRIVE_SCOPE)
            // Both are what make an unattended backup possible. Without
            // `access_type=offline` Google returns an access token and no refresh
            // token, so the app could only ever reach Drive while somebody was
            // watching -- and a scheduled backup is exactly the case where nobody
            // is. `prompt=consent` because Google withholds the refresh token on a
            // re-authorization it considers already granted, which would leave a
            // second sign-in worse off than the first.
            .setAdditionalParameters(
                mapOf("access_type" to "offline", "prompt" to "consent"),
            )
            .build()

    private fun readState(): AuthState =
        preferences.getString(KEY, null)
            ?.let { runCatching { AuthState.jsonDeserialize(it) }.getOrNull() }
            ?: AuthState(GOOGLE)

    private fun writeState(state: AuthState) =
        preferences.edit { putString(KEY, state.jsonSerializeString()) }

    private companion object {
        const val FILE_NAME = "wardrobapp_drive"
        const val KEY = "auth_state"

        /**
         * Google's endpoints, written down rather than discovered.
         *
         * AppAuth can read them from the issuer's discovery document, which is a
         * network round trip before the one that matters -- on a phone, and before
         * anything has been shown. These two addresses have not moved in years,
         * and a fixed address is what the rest of this app's networking uses.
         */
        val GOOGLE = AuthorizationServiceConfiguration(
            "https://accounts.google.com/o/oauth2/v2/auth".toUri(),
            "https://oauth2.googleapis.com/token".toUri(),
        )
    }
}
