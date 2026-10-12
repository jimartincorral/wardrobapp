package com.wardrobapp.presentation

import com.wardrobapp.domain.GarmentReading

/**
 * The photo work the two screens that add garments share: storing what was
 * picked, throwing a stored photo away, reading its colours and what it is,
 * and cutting the garment out of its background.
 *
 * Generic over what a picked photo *is*, for the reason SettingsSource is
 * generic over archives: on the phone it is a `content://` URI from the system
 * picker or the camera, in the browser it will be an uploaded file, and the
 * screen models only ever hand it back. Once stored, a photo is a reference in
 * the form the garment rows hold, which is the same everywhere.
 *
 * The phone's is PhonePhotoWork in :app, over the photo store and ML Kit.
 */
interface PhotoWork<Picked> {
    /** Copy [photo] into the wardrobe's own storage; the reference it is stored under. */
    suspend fun store(photo: Picked): String

    /** Delete a stored photo. One already gone is not a failure. */
    suspend fun delete(photo: String)

    /**
     * The garment's colours, read off a stored photo, most of the garment first;
     * null when the photo cannot be read. A failure here is never worth stopping
     * for -- a garment whose colour was not read is still a garment.
     */
    suspend fun colors(photo: String): List<String>?

    /** Cut the garment in a stored photo out of its background; the cut-out's reference. */
    suspend fun cutOut(photo: String): String

    /**
     * What a stored photo shows -- its type, its attributes, maybe its material
     * and brand -- or null when nothing here can tell.
     *
     * Best effort in every way. The phone asks Gemini Nano where the phone has
     * it and its own style model where that is downloaded, and the browser
     * asks the Home Assistant app's; any of them may be missing, and a reading
     * is only ever a starting point for the form. A default of null, so a
     * platform, or a test, with nothing to read photos with need not say so.
     */
    suspend fun read(photo: String): GarmentReading? = null
}
