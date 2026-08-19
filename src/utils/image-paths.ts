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
 * On web there is no filesystem — photos are data URIs stored inline — so both
 * directions pass those through untouched.
 */

export const GARMENT_IMAGE_DIRNAME = 'garment-images';

/**
 * References that are already portable and must never be reduced to a
 * filename: web data URIs, blob URLs, remote images, and Android SAF documents.
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
 * tests); when it is empty — on web, or before the filesystem is available —
 * the reference is returned unchanged rather than resolved against nothing.
 */
export function resolveImageRef(ref: string, imageDirectory: string): string {
  if (!ref) return '';
  if (NON_FILE_REF.test(ref)) return ref;
  if (!imageDirectory) return ref;
  return `${imageDirectory}${basename(ref)}`;
}

/** True when a reference still carries a directory, i.e. predates the rewrite. */
export function isLegacyAbsoluteImageRef(ref: string): boolean {
  return Boolean(ref) && !NON_FILE_REF.test(ref) && ref.includes('/');
}
