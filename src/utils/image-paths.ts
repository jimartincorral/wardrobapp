/**
 * Garment photo references.
 *
 * Photos live in `<documents>/garment-images/`, but the *absolute* path to that
 * directory is not stable across installs. On iOS the app container carries a
 * UUID that changes on reinstall, so a row holding
 * `file:///var/mobile/Containers/Data/Application/<OLD-UUID>/Documents/garment-images/x.jpg`
 * points nowhere after a restore — even though the file itself was restored
 * correctly one directory over. Every garment then shows a broken image, which
 * is exactly the "move to a new phone" case backups exist for.
 *
 * So the database stores only the filename and the directory is re-attached on
 * read. Rows written by older builds still hold absolute paths, so the resolver
 * also re-bases those onto the current directory: reads are correct immediately,
 * whether or not the migration that rewrites them has run.
 *
 * Values that are not local paths — Android SAF documents, remote URLs, and any
 * inline data — are passed through untouched in both directions.
 */

export const GARMENT_IMAGE_DIRNAME = 'garment-images';

/**
 * References that are already portable and must never be reduced to a
 * filename: Android SAF documents, remote images, and inline data.
 */
const NON_FILE_REF = /^(data:|blob:|https?:|content:)/i;

/** The trailing path segment, or the whole string when there is no separator. */
function basename(ref: string): string {
  const lastSlash = ref.lastIndexOf('/');
  return lastSlash === -1 ? ref : ref.slice(lastSlash + 1);
}

/**
 * Reduce a reference to the portable form kept in the database.
 *
 * Applied at the write boundary, so callers can go on handing around the full
 * URIs that image pickers and `<Image>` sources need.
 */
export function toStoredImageRef(ref: string): string {
  if (!ref) return '';
  if (NON_FILE_REF.test(ref)) return ref;
  return basename(ref);
}

/**
 * Expand a stored reference into something the platform can load.
 *
 * `imageDirectory` is passed explicitly by the caller that knows it (and by
 * tests); when it is empty — before the filesystem is available — the
 * reference is returned unchanged rather than resolved against nothing.
 */
export function resolveImageRef(ref: string, imageDirectory: string): string {
  if (!ref) return '';
  if (NON_FILE_REF.test(ref)) return ref;
  if (!imageDirectory) return ref;
  return `${imageDirectory}${basename(ref)}`;
}

/**
 * True when a reference points at a photo the app already owns.
 *
 * The distinction that keeps a cleanup from deleting live data. The native
 * background-removal module writes its result into the app's files root, and that
 * intermediate is disposable once it has been copied into place -- but the same
 * reference can also be an already-stored cut-out, arriving from a garment being
 * edited, and deleting *that* would take the garment's photo with it.
 *
 * `imageDirectory` is passed in rather than read, exactly as `resolveImageRef`
 * takes it: this file knows the layout, not where the filesystem is mounted.
 */
export function isStoredGarmentImage(ref: string, imageDirectory: string): boolean {
  if (!ref || !imageDirectory) return false;
  if (!ref.startsWith(imageDirectory)) return false;

  // Compared against the directory rather than by finding the folder name
  // anywhere in the path, so a temp file that merely mentions it does not pass --
  // and photos are stored flat, so anything nested is not one of ours.
  return !ref.slice(imageDirectory.length).includes('/');
}

/**
 * References a garment used to hold and no longer does.
 *
 * Worked out by comparing stored *filenames* rather than the references as given,
 * because the same photo can be named either way -- an absolute path from an older
 * build, a resolved `file://` URI from a read, a bare filename from the database --
 * and a mismatch in either direction is a quiet failure: report a file that is
 * still in use and it gets deleted out from under the garment; miss one and it
 * stays on the phone with nothing pointing at it.
 *
 * Only names files, so a caller can decide when it is safe to act -- which is
 * after the row is written, never before.
 */
export function orphanedImageRefs(previous: string[], kept: string[]): string[] {
  const keptNames = new Set(kept.filter(Boolean).map(toStoredImageRef));
  const seen = new Set<string>();

  return previous.filter(ref => {
    if (!ref) return false;

    const name = toStoredImageRef(ref);
    if (keptNames.has(name) || seen.has(name)) return false;

    seen.add(name);
    return true;
  });
}

/** True when a reference still carries a directory, i.e. predates the rewrite. */
export function isLegacyAbsoluteImageRef(ref: string): boolean {
  return Boolean(ref) && !NON_FILE_REF.test(ref) && ref.includes('/');
}
