package com.wardrobapp.presentation

/**
 * Which colours the app has been told to use.
 *
 * [SYSTEM] is not a third palette: it means nothing has been chosen, so the
 * device decides -- the same shape as [LanguageChoice.SYSTEM], and the same
 * reason for keeping it. `src/theme/index.tsx` models it identically, with
 * `system` as the state a fresh install is in.
 *
 * The names match the values the React Native app stores, so a reader comparing
 * the two apps is comparing the same vocabulary. What they are stored *in*
 * differs, and deliberately -- see [themeChoiceFor].
 */
enum class ThemeChoice {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * The choice a stored value stands for.
 *
 * Anything unrecognised -- a value written by a later build, or a preference file
 * that has been edited -- is [SYSTEM] rather than an arbitrary palette, which is
 * what `loadSavedThemeMode` does in the app that ships: it returns null for
 * anything outside the three, and null leaves the mode at `system`.
 */
fun themeChoiceFor(stored: String?): ThemeChoice = when (stored?.trim()?.lowercase()) {
    "light" -> ThemeChoice.LIGHT
    "dark" -> ThemeChoice.DARK
    else -> ThemeChoice.SYSTEM
}

/**
 * The value to store for a choice, or null to store nothing.
 *
 * Null for [SYSTEM] rather than the string "system", so following the device is
 * recorded as the absence of a choice. It means a fresh install and a deliberate
 * return to Automatic are the same state, which is the honest reading of both:
 * there is nothing this app has been asked to override.
 */
val ThemeChoice.storedValue: String?
    get() = when (this) {
        ThemeChoice.SYSTEM -> null
        ThemeChoice.LIGHT -> "light"
        ThemeChoice.DARK -> "dark"
    }

/**
 * Whether to draw in dark colours, given what the device is set to.
 *
 * The device's setting is passed in rather than read here, for the same reason
 * `formatStoredDate` takes its timezone: it is the argument that decides the
 * answer, and a function that reads it from the platform cannot be asked what it
 * would say for the other case.
 */
fun ThemeChoice.usesDarkColors(systemInDark: Boolean): Boolean = when (this) {
    ThemeChoice.SYSTEM -> systemInDark
    ThemeChoice.LIGHT -> false
    ThemeChoice.DARK -> true
}
