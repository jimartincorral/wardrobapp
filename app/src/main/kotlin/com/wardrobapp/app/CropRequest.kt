package com.wardrobapp.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions

/** The garment photo shape: three wide to four tall, everywhere the app shows one. */
private const val ASPECT_WIDTH = 3
private const val ASPECT_HEIGHT = 4

/**
 * What to ask the crop screen for.
 *
 * A photo is cropped on the way in, from the gallery or from the camera, and the
 * ratio is fixed rather than offered: every place a garment photo appears -- the
 * strip in the form, the list, the detail image -- is 3:4, so a photo of any other
 * shape is cropped by the layout instead of by the person who took it. The React
 * Native app asked for exactly this (`allowsEditing: true, aspect: [3, 4]`), and
 * the crop screen behind it was this same library.
 *
 * The colours are passed in rather than read from a theme because this is a View
 * activity being handed the palette of a Compose one. Theme.Wardrobapp.Crop is
 * DayNight, which follows the *system* setting; the app's own light/dark choice
 * lives in Compose, so on a light phone with "Dark" chosen in Settings the crop
 * screen would be the one light screen in the app. Handing it the scheme the app
 * is actually drawing with closes that.
 *
 * `outputCompressQuality` is left at the library's default on purpose: this file
 * is an intermediate that AndroidPhotoStore immediately re-encodes at 70, and
 * compressing twice only loses detail.
 */
internal fun cropTo3by4(source: Uri, output: Uri, colors: ColorScheme) =
    CropImageContractOptions(
        source,
        CropImageOptions().apply {
            fixAspectRatio = true
            aspectRatioX = ASPECT_WIDTH
            aspectRatioY = ASPECT_HEIGHT
            // Open with the crop window on the whole photo rather than inset from
            // it: the common case is keeping most of the frame.
            initialCropWindowPaddingRatio = 0f

            customOutputUri = output
            outputCompressFormat = Bitmap.CompressFormat.JPEG

            activityBackgroundColor = colors.background.toArgb()
            toolbarColor = colors.surface.toArgb()
            toolbarTitleColor = colors.onSurface.toArgb()
            toolbarBackButtonColor = colors.onSurface.toArgb()
            toolbarTintColor = colors.onSurface.toArgb()
            activityMenuIconColor = colors.onSurface.toArgb()
            activityMenuTextColor = colors.onSurface.toArgb()
        },
    )
