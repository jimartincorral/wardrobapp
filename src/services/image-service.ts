import * as ImageManipulator from 'expo-image-manipulator';
import { Image, Platform } from 'react-native';
import * as Crypto from 'expo-crypto';

const MAX_DIMENSION = 800;
const JPEG_QUALITY = 0.7;

// ─── Web helpers ────────────────────────────────────────────────────
// On web there is no filesystem. Images are stored as persistent
// data-URIs (base64) so they survive page reloads and live in SQLite.

async function blobUrlToDataUri(blobUrl: string): Promise<string> {
  const res = await fetch(blobUrl);
  const blob = await res.blob();
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onloadend = () => resolve(reader.result as string);
    reader.onerror = reject;
    reader.readAsDataURL(blob);
  });
}

async function compressOnWeb(sourceUri: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const img = new window.Image();
    img.crossOrigin = 'anonymous';
    img.onload = () => {
      const scale = Math.min(1, MAX_DIMENSION / Math.max(img.width, img.height));
      const w = Math.round(img.width * scale);
      const h = Math.round(img.height * scale);

      const canvas = document.createElement('canvas');
      canvas.width = w;
      canvas.height = h;
      const ctx = canvas.getContext('2d')!;
      ctx.drawImage(img, 0, 0, w, h);

      resolve(canvas.toDataURL('image/jpeg', JPEG_QUALITY));
    };
    img.onerror = () => reject(new Error('Failed to load image for compression'));
    img.src = sourceUri;
  });
}

// ─── Native helpers ─────────────────────────────────────────────────

let FileSystem: typeof import('expo-file-system/legacy') | null = null;

function getFileSystem() {
  if (!FileSystem) {
    FileSystem = require('expo-file-system/legacy');
  }
  return FileSystem!;
}

function getImageDir(): string {
  const fs = getFileSystem();
  return `${fs.documentDirectory}garment-images/`;
}

function getImportTempDir(): string {
  const fs = getFileSystem();
  return `${fs.cacheDirectory ?? fs.documentDirectory}garment-imports/`;
}

async function ensureImageDir() {
  const fs = getFileSystem();
  const dir = getImageDir();
  const dirInfo = await fs.getInfoAsync(dir);
  if (!dirInfo.exists) {
    await fs.makeDirectoryAsync(dir, { intermediates: true });
  }
}

async function ensureImportTempDir() {
  const fs = getFileSystem();
  const dir = getImportTempDir();
  const dirInfo = await fs.getInfoAsync(dir);
  if (!dirInfo.exists) {
    await fs.makeDirectoryAsync(dir, { intermediates: true });
  }
}

// ─── Public API ─────────────────────────────────────────────────────

/**
 * Compress and save an image from a source URI.
 * Returns a data URI (web) or local file path (native).
 */
export async function compressAndSaveImage(sourceUri: string): Promise<string> {
  if (Platform.OS === 'web') {
    return compressOnWeb(sourceUri);
  }

  // Native path — use expo-image-manipulator + filesystem
  await ensureImageDir();
  const fs = getFileSystem();

  const manipulated = await ImageManipulator.manipulateAsync(
    sourceUri,
    [{ resize: { width: MAX_DIMENSION } }],
    { compress: JPEG_QUALITY, format: ImageManipulator.SaveFormat.JPEG }
  );

  const filename = `${Crypto.randomUUID()}.jpg`;
  const destUri = `${getImageDir()}${filename}`;
  await fs.copyAsync({ from: manipulated.uri, to: destUri });

  return destUri;
}

/**
 * Download a remote image into the app cache so it can flow through native image tooling.
 */
export async function downloadRemoteImageToTempFile(sourceUrl: string): Promise<string> {
  if (Platform.OS === 'web') {
    return sourceUrl;
  }

  await ensureImportTempDir();
  const fs = getFileSystem();

  const extensionMatch = sourceUrl.match(/\.(jpg|jpeg|png|webp|gif|bmp|avif)(?:$|[?#])/i);
  const extension = extensionMatch?.[1]?.toLowerCase() ?? 'jpg';
  const filename = `${Crypto.randomUUID()}.${extension}`;
  const destination = `${getImportTempDir()}${filename}`;

  const result = await fs.downloadAsync(sourceUrl, destination, {
    headers: {
      Accept: 'image/*,*/*;q=0.8',
    },
  });

  if (result.status && result.status >= 400) {
    throw new Error(`Image download failed (${result.status}).`);
  }

  return result.uri;
}

/**
 * Save a background-removed image (PNG to preserve transparency).
 */
export async function saveBgRemovedImage(sourceUri: string): Promise<string> {
  if (Platform.OS === 'web') {
    // sourceUri is a blob URL from removeBackground — convert to persistent data URI
    return blobUrlToDataUri(sourceUri);
  }

  await ensureImageDir();
  const fs = getFileSystem();

  // Downscale to the same cap as regular photos before storing. Background
  // removal returns a full-resolution image, and copying it verbatim is what
  // bloats storage and backups. Keep PNG so the transparent background is
  // preserved — JPEG has no alpha channel, so it would fill transparency with
  // black. The resize is the dominant size win here.
  const manipulated = await ImageManipulator.manipulateAsync(
    sourceUri,
    [{ resize: { width: MAX_DIMENSION } }],
    { format: ImageManipulator.SaveFormat.PNG }
  );

  const filename = `${Crypto.randomUUID()}_nobg.png`;
  const destUri = `${getImageDir()}${filename}`;
  await fs.copyAsync({ from: manipulated.uri, to: destUri });

  return destUri;
}

/**
 * Delete an image file.
 */
export async function deleteImage(uri: string): Promise<void> {
  if (Platform.OS === 'web') {
    // Revoke blob URLs to free memory; data URIs need no cleanup
    if (uri.startsWith('blob:')) {
      URL.revokeObjectURL(uri);
    }
    return;
  }

  try {
    const fs = getFileSystem();
    const info = await fs.getInfoAsync(uri);
    if (info.exists) {
      await fs.deleteAsync(uri);
    }
  } catch {
    // Silently ignore - file may already be deleted
  }
}

/**
 * Get the size of an image file in bytes.
 */
export async function getImageSize(uri: string): Promise<number> {
  if (Platform.OS === 'web') {
    // Estimate from data URI length (base64 is ~4/3 of original)
    if (uri.startsWith('data:')) {
      const base64Part = uri.split(',')[1] || '';
      return Math.round(base64Part.length * 0.75);
    }
    if (uri.startsWith('blob:')) {
      try {
        const res = await fetch(uri);
        const blob = await res.blob();
        return blob.size;
      } catch {
        return 0;
      }
    }
    return 0;
  }

  try {
    const fs = getFileSystem();
    const info = await fs.getInfoAsync(uri);
    return info.exists && 'size' in info ? info.size : 0;
  } catch {
    return 0;
  }
}

function getImageDimensions(uri: string): Promise<{ width: number; height: number }> {
  return new Promise((resolve, reject) => {
    Image.getSize(uri, (width, height) => resolve({ width, height }), reject);
  });
}

export type RecompressResult = {
  processed: number;
  recompressed: number;
  bytesSaved: number;
};

/**
 * One-time cleanup for background-removed images saved before they were
 * downscaled on import. Finds every stored `_nobg.png`, and any that is still
 * larger than the current cap is resized to it in place (URI unchanged, so DB
 * references stay valid). Regular JPEGs were always downscaled, so they're left
 * alone. Safe to run repeatedly — already-small images are skipped.
 */
export async function recompressLegacyBgRemovedImages(
  onProgress?: (done: number, total: number) => void
): Promise<RecompressResult> {
  if (Platform.OS === 'web') return { processed: 0, recompressed: 0, bytesSaved: 0 };

  const fs = getFileSystem();
  const dir = getImageDir();
  const dirInfo = await fs.getInfoAsync(dir);
  if (!dirInfo.exists) return { processed: 0, recompressed: 0, bytesSaved: 0 };

  const files = (await fs.readDirectoryAsync(dir)).filter((name) => name.endsWith('_nobg.png'));
  let recompressed = 0;
  let bytesSaved = 0;

  for (let i = 0; i < files.length; i++) {
    const uri = `${dir}${files[i]}`;
    try {
      const { width } = await getImageDimensions(uri);
      if (width > MAX_DIMENSION) {
        const before = await getImageSize(uri);
        const resized = await ImageManipulator.manipulateAsync(
          uri,
          [{ resize: { width: MAX_DIMENSION } }],
          { format: ImageManipulator.SaveFormat.PNG }
        );
        // Overwrite the original file so the stored URI (and every DB row that
        // references it) keeps pointing at the smaller image.
        await fs.deleteAsync(uri, { idempotent: true });
        await fs.copyAsync({ from: resized.uri, to: uri });
        const after = await getImageSize(uri);
        recompressed++;
        bytesSaved += Math.max(0, before - after);
      }
    } catch (error) {
      console.warn('Failed to recompress image, skipping:', files[i], error);
    }
    onProgress?.(i + 1, files.length);
  }

  return { processed: files.length, recompressed, bytesSaved };
}

/**
 * Get total storage used by garment images in MB.
 */
export async function getTotalImageStorage(): Promise<number> {
  if (Platform.OS === 'web') {
    // No filesystem on web — can't enumerate stored images easily
    return 0;
  }

  try {
    const fs = getFileSystem();
    const dir = getImageDir();
    const dirInfo = await fs.getInfoAsync(dir);
    if (!dirInfo.exists) return 0;

    const files = await fs.readDirectoryAsync(dir);
    let totalBytes = 0;

    for (const file of files) {
      const fileInfo = await fs.getInfoAsync(`${dir}${file}`);
      if (fileInfo.exists && 'size' in fileInfo) {
        totalBytes += fileInfo.size;
      }
    }

    return totalBytes / (1024 * 1024);
  } catch {
    return 0;
  }
}
