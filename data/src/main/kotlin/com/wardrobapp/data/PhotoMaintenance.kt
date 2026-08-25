package com.wardrobapp.data

/**
 * Tidying up photos stored before they were stored properly.
 *
 * One job: cut-outs saved by a build that wrote them at full resolution. Ordinary
 * photos were always scaled on import, so they need nothing; cut-outs were not,
 * and a wardrobe carried over from an early version can hold several megabytes of
 * transparent PNG that every backup then carries too.
 *
 * A port of `recompressLegacyBgRemovedImages` in `src/services/image-service.ts`,
 * with the decisions pulled out of the loop so they can be asked without a
 * filesystem. What is left for :app is the part that needs Android: decoding a
 * PNG, scaling it, and writing it back over itself.
 *
 * One deliberate difference. The TypeScript asks whether a photo is wider than the
 * cap; this asks whether it is *bigger* than the cap, which for a tall cut-out is
 * a different question. That is not a new rule -- it is the rule this app already
 * stores photos by ([storedPhotoSize] caps the longest side, because scaling a
 * portrait by its width leaves it taller than the cap) -- so a pass that used the
 * width alone would leave behind exactly the files this app would not have written.
 */

/** A stored cut-out, as the maintenance pass sees it. */
data class StoredCutout(
    val name: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
)

/**
 * Whether this app would store the file smaller than it is.
 *
 * The same question [storedPhotoSize] answers, asked of a file already on disk: if
 * the size it would be stored at differs from the size it is, it is oversized. A
 * photo whose dimensions cannot be read is left alone -- an unreadable file is not
 * something to rewrite.
 */
fun cutoutNeedsShrinking(cutout: StoredCutout): Boolean {
    if (cutout.width <= 0 || cutout.height <= 0) return false

    val target = storedPhotoSize(cutout.width, cutout.height)
    return target.width != cutout.width || target.height != cutout.height
}

/**
 * The cut-outs among a directory's files, oversized ones first.
 *
 * Filtered by [isCutoutFilename] rather than by "is it a PNG", because that suffix
 * is the contract cut-outs have always been written under, and an ordinary photo has already
 * been scaled.
 *
 * Sorted largest first so that a pass interrupted partway -- the app being killed,
 * the phone running out of room -- has done the most good it could with the time it
 * had.
 */
fun cutoutsToShrink(files: List<StoredCutout>): List<StoredCutout> =
    files
        .filter { isCutoutFilename(it.name) && cutoutNeedsShrinking(it) }
        .sortedWith(compareByDescending<StoredCutout> { it.bytes }.thenBy { it.name })

/** What a maintenance pass came to. */
data class MaintenanceSummary(
    /** How many files were looked at, including the ones left alone. */
    val examined: Int,
    /** How many cut-outs were rewritten smaller. */
    val shrunk: Int,
    val bytesSaved: Long,
    /** How many files were deleted because nothing pointed at them. */
    val deleted: Int = 0,
) {
    /** Whether the pass did anything, which is what decides what it reports. */
    val changedAnything: Boolean get() = shrunk > 0 || deleted > 0
}

/** Two passes over the same directory, reported as one. */
fun MaintenanceSummary.and(other: MaintenanceSummary): MaintenanceSummary = MaintenanceSummary(
    // Not a sum: both passes look at the same directory, and a wardrobe of ten
    // photos that reports twenty examined is telling the reader nothing true.
    examined = maxOf(examined, other.examined),
    shrunk = shrunk + other.shrunk,
    bytesSaved = bytesSaved + other.bytesSaved,
    deleted = deleted + other.deleted,
)

/**
 * Add up what a pass saved.
 *
 * Per-file savings are floored at zero: re-encoding can make a file *bigger* --
 * a PNG of a photograph often does -- and a total that went down because one file
 * went up would read as a smaller saving rather than as the two separate facts it
 * is. The file is still counted as shrunk, because its dimensions did come down,
 * which is what the next backup cares about.
 */
fun maintenanceSummary(examined: Int, savings: List<Long>): MaintenanceSummary =
    MaintenanceSummary(
        examined = examined,
        shrunk = savings.size,
        bytesSaved = savings.sumOf { it.coerceAtLeast(0L) },
    )
