import { Platform } from 'react-native';

/**
 * Background removal.
 *
 * Runs on-device via @six33/react-native-bg-removal, which needs the native
 * module linked — so a development or release build, not Expo Go.
 */

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

let nativeBackgroundModule: NativeBackgroundModule | null = null;

/**
 * Whether background removal can run here at all.
 *
 * This only rules out platforms that have no implementation; whether the native
 * module is actually linked into *this* build is not knowable synchronously, so
 * removeImageBackground still reports that case as an error.
 */
export function isBackgroundRemovalAvailable(): boolean {
  return Platform.OS === 'android';
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
 * Remove the background from an image, returning a local file URI for the
 * result. Throws if it is unavailable on this device or in this build.
 */
export async function removeImageBackground(
  imageUri: string,
  onProgress?: (progress: BackgroundRemovalProgress) => void
): Promise<string> {
  if (!imageUri) throw new Error('Image URI is required');

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
