package com.wardrobapp.presentation

/**
 * What the home screen is given to draw.
 *
 * Was `HomeViewModel.State` in :app. It moved here unchanged when the screens
 * started moving to Compose Multiplatform: a screen's state is what a shared
 * screen takes, so it has to be common code, and here it is compiled and
 * tested on every machine rather than only where there is an Android SDK. The
 * ViewModel that fills it stays in :app.
 */

data class HomeScreenState(
    val loading: Boolean = true,
    val items: Long = 0,
    val archived: Long = 0,
    /**
     * How many ratings have ever been given.
     *
     * Not shown anywhere. It is read here because it is the only thing the
     * first-steps card cannot work out from the two counts above, and this
     * screen is already reading the wardrobe -- a second model for one number
     * would be a second read on every visit to Home.
     */
    val rated: Long = 0,
    /**
     * Reported rather than swallowed.
     *
     * The React Native screen logs a failure to the console and leaves both
     * counts at zero, which reads as an empty wardrobe -- the one thing a
     * wardrobe app must not say when it cannot tell.
     */
    val error: String? = null,
)
