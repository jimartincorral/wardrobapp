package com.wardrobapp.app

import android.app.Application
import android.content.ContentProvider
import android.content.pm.ProviderInfo

/**
 * The application every Robolectric test runs in, named in robolectric.properties.
 *
 * It exists for one thing: starting Compose Multiplatform's resources the way a
 * real device does. The screens' strings are Compose Multiplatform resources,
 * packaged as Android assets, and the library reaches those assets through a
 * Context it is handed by a ContentProvider of its own -- AndroidContextProvider,
 * declared in its manifest and merged into the app's. On a phone Android
 * creates every declared provider before the first activity, so the Context is
 * always there. Robolectric does not, and the first `stringResource` in a test
 * failed with "Android context is not initialized", which is how CI 328 went.
 *
 * So the provider is created here exactly as Android creates one: constructed,
 * then attached, which is what calls its onCreate and hands it this application.
 * The ProviderInfo carries an authority because the provider checks it -- it
 * refuses the library's own placeholder package, which is how it notices a
 * build that never substituted an applicationId -- and the app's package is
 * what the merged manifest substitutes.
 *
 * By name rather than by class reference because the provider is an
 * implementation detail of the library rather than API. If an upgrade renames
 * it, this fails on every test at startup with the name in the message, which
 * is the loud way for that to break.
 *
 * Tried first and rejected: PreviewContextConfigurationEffect, the library's
 * own hook for this. It sets the Context only while LocalInspectionMode is on,
 * so using it would mean running every screen test in inspection mode, which
 * changes what some composables draw. A test that renders something other than
 * what ships is not testing it.
 */
class RobolectricTestApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val provider = Class.forName(COMPOSE_RESOURCES_PROVIDER)
            .getDeclaredConstructor()
            .newInstance() as ContentProvider
        provider.attachInfo(
            this,
            ProviderInfo().apply {
                authority = "$packageName.resources.AndroidContextProvider"
                name = COMPOSE_RESOURCES_PROVIDER
            },
        )
    }

    private companion object {
        const val COMPOSE_RESOURCES_PROVIDER = "org.jetbrains.compose.resources.AndroidContextProvider"
    }
}
