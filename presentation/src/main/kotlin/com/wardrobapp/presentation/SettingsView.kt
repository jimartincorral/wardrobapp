package com.wardrobapp.presentation

/**
 * What the settings screen shows.
 *
 * The screen itself is mostly buttons, so what is worth pulling out here is the
 * arithmetic: the storage figures and how far a backup has got. Both are the kind
 * of thing that is wrong without looking wrong -- `settings.tsx` computes
 * `(bytes / 1024 / 1024).toFixed(1)` inline in three separate places, which is
 * three chances to divide once too few times.
 */

/** The storage rows, as text ready to render. */
data class SettingsView(
    val garments: Long,
    val retired: Long,
    val photoMegabytes: String,
)

fun settingsView(garments: Long, retired: Long, photoBytes: Long): SettingsView =
    SettingsView(
        garments = garments,
        retired = retired,
        photoMegabytes = formatMegabytes(photoBytes),
    )

private const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0

/**
 * Bytes as megabytes, to one decimal place.
 *
 * One decimal because the number is only there to answer "is this a lot?", and
 * because rounding to whole megabytes makes every wardrobe under half a megabyte
 * report zero -- which reads as a bug rather than as a small wardrobe.
 */
fun formatMegabytes(bytes: Long): String {
    val megabytes = bytes / BYTES_PER_MEGABYTE
    // Rounded rather than formatted with a locale-aware pattern: the unit is
    // appended by the screen, and a decimal comma here would fight the "MB" the
    // screen writes after it.
    val tenths = Math.round(megabytes * 10.0)
    return "${tenths / 10}.${tenths % 10}"
}

/**
 * Where a backup has got to.
 *
 * Three phases, not the React Native app's four. It stages, zips into the cache
 * and *then* streams the zip into the folder the user granted, so it has a
 * separate "saving" step; this writes the archive straight into the stream the
 * document picker handed back, so archiving and saving are one pass.
 */
enum class BackupPhase {
    /** Copying the database aside, with the connection closed. */
    STAGING,

    /** Writing the archive out, photo by photo. */
    ARCHIVING,

    DONE,
}

/** Where the bar sits when staging is all that has happened. */
private const val STAGING_PERCENT = 5

/**
 * How full the progress bar should be, 0 to 100.
 *
 * Photos are the only part whose duration scales with the wardrobe, so they get
 * the whole track after staging. A wardrobe with no photos jumps from 5 to 100,
 * which is honest: there was nothing in between.
 */
fun backupPercent(phase: BackupPhase, copied: Int, total: Int): Int = when (phase) {
    BackupPhase.STAGING -> STAGING_PERCENT
    BackupPhase.DONE -> 100
    BackupPhase.ARCHIVING -> {
        if (total <= 0) {
            STAGING_PERCENT
        } else {
            val done = copied.coerceIn(0, total).toDouble() / total
            STAGING_PERCENT + Math.round(done * (100 - STAGING_PERCENT)).toInt()
        }
    }
}
