import { Platform } from 'react-native';

/**
 * Background removal service.
 *
 * Uses @imgly/background-removal on web (WASM-based, runs locally).
 * On native (Android/iOS), this requires a development build.
 *
 * The @imgly/background-removal package downloads a ~40MB ONNX model on first use.
 * After that, it's cached and runs entirely offline.
 */

const IMGLY_LOCAL_MODULE_URL = '/vendor/imgly-background-removal.bundle.mjs';

type WebRemoveBackground = (image: Blob, config?: {
  progress?: (key: string, current: number, total: number) => void;
}) => Promise<Blob>;

type NativeRemoveBackground = (imageURI: string, options?: { trim?: boolean }) => Promise<string>;
type NativeIsSupported = () => Promise<boolean>;

interface NativeBackgroundModule {
  removeBackground: NativeRemoveBackground;
  isNativeBackgroundRemovalSupported: NativeIsSupported;
}

export interface BackgroundRemovalProgress {
  key: string;
  current: number;
  total: number;
  percent: number;
}

let webRemoveBackground: WebRemoveBackground | null = null;
let nativeBackgroundModule: NativeBackgroundModule | null = null;

async function importFromUrl<T>(url: string): Promise<T> {
  const importer = new Function('moduleUrl', 'return import(moduleUrl);') as (moduleUrl: string) => Promise<T>;
  return importer(url);
}

/**
 * Check if background removal is available on this platform.
 */
export function isBackgroundRemovalAvailable(): boolean {
  return Platform.OS === 'web' || Platform.OS === 'android' || Platform.OS === 'ios';
}

async function loadNativeBackgroundModule(): Promise<NativeBackgroundModule> {
  if (nativeBackgroundModule) return nativeBackgroundModule;

  const mod = await import('@six33/react-native-bg-removal') as Partial<NativeBackgroundModule>;
  if (!mod.removeBackground || !mod.isNativeBackgroundRemovalSupported) {
    throw new Error('Native background removal module is missing required exports');
  }

  nativeBackgroundModule = {
    removeBackground: mod.removeBackground,
    isNativeBackgroundRemovalSupported: mod.isNativeBackgroundRemovalSupported,
  };

  return nativeBackgroundModule;
}

/**
 * Remove the background from an image.
 * Returns a blob URL (web) or local file URI (native) of the result.
 * Throws if not available on this platform.
 */
export async function removeImageBackground(
  imageUri: string,
  onProgress?: (progress: BackgroundRemovalProgress) => void
): Promise<string> {
  if (!imageUri) throw new Error('Image URI is required');

  if (Platform.OS === 'web') {
    return removeBackgroundWeb(imageUri, onProgress);
  }

  return removeBackgroundNative(imageUri, onProgress);
}

async function removeBackgroundNative(
  imageUri: string,
  onProgress?: (progress: BackgroundRemovalProgress) => void
): Promise<string> {
  try {
    onProgress?.({ key: 'native-start', current: 1, total: 100, percent: 8 });

    const nativeModule = await loadNativeBackgroundModule();
    const supported = await nativeModule.isNativeBackgroundRemovalSupported();

    if (!supported) {
      throw new Error('REQUIRES_API_FALLBACK');
    }

    onProgress?.({ key: 'native-processing', current: 35, total: 100, percent: 35 });

    const resultUri = await nativeModule.removeBackground(imageUri, { trim: false });

    onProgress?.({ key: 'native-finish', current: 100, total: 100, percent: 100 });
    return resultUri;
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);

    if (message === 'REQUIRES_API_FALLBACK') {
      throw new Error('Background removal requires an API fallback on this device.');
    }

    if (message.includes('TurboModuleRegistry') || message.includes('Native module')) {
      throw new Error('Native background removal is not available in this build. Use a development/production build.');
    }

    throw new Error(`Background removal failed: ${message}`);
  }
}

async function removeBackgroundWeb(
  imageUri: string,
  onProgress?: (progress: BackgroundRemovalProgress) => void
): Promise<string> {
  try {
    if (!webRemoveBackground) {
      const imglyModule = await importFromUrl<{ removeBackground?: WebRemoveBackground }>(IMGLY_LOCAL_MODULE_URL);
      const removeBg = (imglyModule as { removeBackground?: WebRemoveBackground }).removeBackground;
      if (!removeBg) {
        throw new Error('Background removal module missing removeBackground export');
      }
      webRemoveBackground = removeBg;
    }

    // Fetch the image as a blob
    const response = await fetch(imageUri);
    const blob = await response.blob();

    // Process - this downloads the model on first run (~40MB)
    const resultBlob = await webRemoveBackground(blob, {
      progress: (key: string, current: number, total: number) => {
        const percent = total > 0 ? Math.min(100, Math.max(0, Math.round((current / total) * 100))) : 0;
        onProgress?.({ key, current, total, percent });
      },
    });

    // Convert result blob to URL
    return URL.createObjectURL(resultBlob);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error('Background removal failed:', error);
    throw new Error(`Background removal failed: ${message}`);
  }
}
