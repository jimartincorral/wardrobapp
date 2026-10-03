package com.wardrobapp.presentation

/**
 * What a screen says went wrong, when the failure itself has nothing to say.
 *
 * Most failures on the editing screens arrive as an exception whose message is
 * either absent or written for a developer, so a screen shows its own sentence
 * instead: "That garment could not be saved." These are those sentences, as
 * reasons rather than words, for the same reason [UnsafeUrlReason] and the
 * archive failures are -- the screen is where the reader's language is known.
 *
 * They were Android string resource ids until the screens' state moved into
 * common code, where there is no `R.string` to point at. Each value is named
 * after the resource it stands for, so the mapping in :app reads one to one.
 */
enum class ErrorFallback {
    BACKGROUND_NOT_REMOVED,
    PHOTO_NOT_CROPPED,
    GARMENT_NOT_SAVED,
    NO_CAMERA,
    OUTFIT_NOT_DELETED,
    OUTFIT_NOT_SAVED,
    PHOTO_NOT_IMPORTED,
    PHOTO_REQUIRED,
    RATING_NOT_SAVED,
    WARDROBE_UNREADABLE,
    GARMENT_NOT_DELETED,
    NOT_UNDONE,
}

/**
 * What the garment form's error dialog is titled.
 *
 * [SAVE] unless the failure was about a photo or a background, which are not
 * saves -- see `GarmentFormScreenState.errorTitle`.
 */
enum class ErrorTitle {
    SAVE,
    PHOTO,
    BACKGROUND,
}
