package com.wardrobapp.data

/**
 * Why an archive cannot be restored, as a value rather than a sentence.
 *
 * The sentences came first and are still here: `archive-validation.jsonl` pins
 * 41 cases against the React Native app's own wording and compares the *message*,
 * not just accept-or-reject, so changing that text would mean editing the app
 * that ships for this one's convenience. What was missing is a way for a screen
 * to say the same thing in the reader's language, and a string cannot be
 * translated after the fact.
 *
 * So the message is derived from the reason ([englishMessage]) and the reason
 * travels on the exception. `:data` keeps one English source, the fixture keeps
 * comparing it, and `:app` maps the same reason to a string resource --
 * `ArchiveMessageParityTest` holds those two Englishes to each other so they
 * cannot drift apart unnoticed.
 *
 * The shape is not a flat list because the messages are not flat. Restoring wraps:
 * a database that fails its integrity check produces a fragment, which the caller
 * folds into "Invalid backup: … . Nothing was changed." And some failures are the
 * JDK's or SQLite's, whose words this app did not write and cannot translate.
 * [ArchiveDetail] is that distinction, made explicit rather than lost in string
 * concatenation.
 */
sealed interface UnrestorableReason {

    // -- The manifest, or the legacy payload that stood in for one -------------
    //
    // One case each rather than one per file, because the two differ only in
    // which name they mention.

    data class ManifestUnreadable(val name: String) : UnrestorableReason

    data class ManifestNotABackup(val name: String) : UnrestorableReason

    data class ManifestVersionMissing(val name: String) : UnrestorableReason

    data class ManifestNotFound(val name: String) : UnrestorableReason

    // -- Versions -------------------------------------------------------------

    /**
     * Written by a build that reads a format this one does not.
     *
     * Distinct from [UnsupportedVersion] because the answer differs: this one is
     * fixed by updating the app, and that one is not fixed by anything.
     */
    data class BackupFromNewerApp(val found: Int, val supported: Int) : UnrestorableReason

    /** `readable` is a list, so it is text rather than a number. */
    data class UnsupportedVersion(val found: Int, val readable: String) : UnrestorableReason

    // -- What the archive contains -------------------------------------------

    data class DatabaseMissing(val name: String) : UnrestorableReason

    data class DatabaseEmpty(val name: String) : UnrestorableReason

    data object NoDatabase : UnrestorableReason

    data class ArchiveTruncated(val expected: Int, val present: Int) : UnrestorableReason

    data object NotBase64 : UnrestorableReason

    /** A zip entry whose path climbs out of the archive. */
    data class EntryOutsideArchive(val entry: String) : UnrestorableReason

    // -- The staged database --------------------------------------------------

    /** A fragment: the caller folds it into [InvalidBackup]. */
    data class IntegrityCheckFailed(val result: String) : UnrestorableReason

    // -- Wrapping -------------------------------------------------------------

    /** Rejected before anything was touched. */
    data class InvalidBackup(val detail: ArchiveDetail) : UnrestorableReason

    /** Failed partway, and the wardrobe was put back. */
    data class RestoreFailed(val detail: ArchiveDetail) : UnrestorableReason

    /**
     * Failed partway, and putting the wardrobe back failed too.
     *
     * The worst case, and the reason it names both files: they are where the
     * user's data still is.
     */
    data class RollbackFailed(
        val detail: ArchiveDetail,
        val rollbackDetail: ArchiveDetail,
        val databaseName: String,
        val imagesName: String,
    ) : UnrestorableReason
}

/** What a wrapping failure was caused by. */
sealed interface ArchiveDetail {

    /** Something this app decided, so it can be said in any language. */
    data class Known(val reason: UnrestorableReason) : ArchiveDetail

    /**
     * Someone else's words -- SQLite's, or the JDK's.
     *
     * Kept because they are the only diagnostic there is, and named `Foreign` so
     * that a reader of `:app` can see which half of a sentence will stay English
     * whatever the language.
     */
    data class Foreign(val text: String) : ArchiveDetail
}

/**
 * The sentence this reason has always produced.
 *
 * Byte-for-byte what the messages were before they had reasons behind them, which
 * is what lets `archive-validation.jsonl` and `ArchiveRestoreTest` stay untouched.
 */
fun UnrestorableReason.englishMessage(): String = when (this) {
    is UnrestorableReason.ManifestUnreadable ->
        "Invalid backup: $name is not readable JSON."

    is UnrestorableReason.ManifestNotABackup ->
        "Invalid backup: $name does not describe a backup."

    is UnrestorableReason.ManifestVersionMissing ->
        "Invalid backup: $name has no version number."

    is UnrestorableReason.ManifestNotFound ->
        "Invalid backup archive: no $name found"

    is UnrestorableReason.BackupFromNewerApp ->
        "This backup was made by a newer version of Wardrobapp (backup format " +
            "$found; this app reads $supported). Update the app and try again."

    is UnrestorableReason.UnsupportedVersion ->
        "Unsupported backup format $found; this app reads $readable."

    is UnrestorableReason.DatabaseMissing ->
        "Invalid backup: $name is missing from the archive. Nothing was changed."

    is UnrestorableReason.DatabaseEmpty ->
        "Invalid backup: $name is empty. Nothing was changed."

    UnrestorableReason.NoDatabase ->
        "Invalid backup: it contains no database. Nothing was changed."

    is UnrestorableReason.ArchiveTruncated ->
        "Incomplete backup: the manifest lists $expected photo(s) but only " +
            "$present are present, so the archive is truncated. Nothing was changed."

    UnrestorableReason.NotBase64 ->
        "Invalid backup: its contents are not valid base64. Nothing was changed."

    is UnrestorableReason.EntryOutsideArchive ->
        "Invalid backup: the archive contains an entry outside itself " +
            "($entry). Nothing was changed."

    is UnrestorableReason.IntegrityCheckFailed ->
        "it failed SQLite's integrity check ($result)"

    is UnrestorableReason.InvalidBackup ->
        "Invalid backup: ${detail.englishText()}. Nothing was changed."

    is UnrestorableReason.RestoreFailed ->
        "Restore failed: ${detail.englishText()}. Your wardrobe was left unchanged."

    is UnrestorableReason.RollbackFailed ->
        "Restore failed (${detail.englishText()}) and the wardrobe could not be " +
            "put back (${rollbackDetail.englishText()}). Your original data is " +
            "still on the device as $databaseName and $imagesName."
}

/** The detail as it reads in English, whoever wrote it. */
fun ArchiveDetail.englishText(): String = when (this) {
    is ArchiveDetail.Known -> reason.englishMessage()
    is ArchiveDetail.Foreign -> text
}
