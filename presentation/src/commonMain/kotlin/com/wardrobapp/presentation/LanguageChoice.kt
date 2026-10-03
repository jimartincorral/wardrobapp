package com.wardrobapp.presentation

/**
 * Which language the app has been told to use.
 *
 * [SYSTEM] is not a third language: it means nothing has been chosen, so the
 * device decides. The app this replaced cannot express it -- its picker offers two
 * options and stores one the first time it runs -- but Android can, and its own
 * per-app language screen shows it as "System default". Keeping it means "I never
 * chose" stays distinguishable from "I chose English", which is what makes moving
 * a phone to Spanish work as expected.
 */
enum class LanguageChoice {
    SYSTEM,
    ENGLISH,
    SPANISH,
}

/**
 * The choice a stored language tag stands for.
 *
 * Matched on the language subtag alone, so `es-419` and `es-ES` are both Spanish
 * -- the same rule as `getSupportedLocale` in src/i18n/index.ts, which takes the
 * first two characters. Anything unrecognised is [SYSTEM] rather than English:
 * the app has no translation for it either way, and the device's own idea of what
 * to fall back to is better than this app's.
 */
fun languageChoiceFor(languageTags: String): LanguageChoice {
    val language = languageTags.trim().takeWhile { it != '-' && it != '_' && it != ',' }.lowercase()

    return when (language) {
        "es" -> LanguageChoice.SPANISH
        "en" -> LanguageChoice.ENGLISH
        else -> LanguageChoice.SYSTEM
    }
}

/**
 * The tag to store for a choice, or null to store nothing.
 *
 * Null and not an empty string, so a caller has to decide what "no choice" means
 * for the API it is talking to rather than passing a blank through by accident.
 */
val LanguageChoice.languageTag: String?
    get() = when (this) {
        LanguageChoice.SYSTEM -> null
        LanguageChoice.ENGLISH -> "en"
        LanguageChoice.SPANISH -> "es"
    }
