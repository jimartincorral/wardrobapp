const { withAndroidManifest, AndroidConfig } = require('expo/config-plugins');

const META_DATA_NAME = 'com.google.mlkit.vision.DEPENDENCIES';

/**
 * ML Kit models to bundle, comma separated.
 *
 * `subject_segment` is what background removal needs. `barcode_ui` comes from
 * expo-camera, which this app never imports directly -- it is only present to
 * satisfy a peer dependency of @tensorflow/tfjs-react-native. Keeping it
 * matches the manifest the app has been building with locally; dropping it
 * would trim the barcode model out of the APK.
 */
const ML_KIT_DEPENDENCIES = 'barcode_ui,subject_segment';

const TOOLS_NAMESPACE = 'http://schemas.android.com/tools';

/**
 * Force a single value for the ML Kit vision meta-data.
 *
 * Two libraries declare this same meta-data name with different values --
 * @six33/react-native-bg-removal wants `subject_segment`, expo-camera wants
 * `barcode_ui` -- and the manifest merger fails rather than picking one:
 *
 *   Attribute meta-data#com.google.mlkit.vision.DEPENDENCIES@value
 *   value=(subject_segment) from [:six33_react-native-bg-removal]
 *   is also present at [host.exp.exponent:expo.modules.camera:55.0.9] value=(barcode_ui)
 *
 * Declaring it in the app manifest with `tools:replace="android:value"`
 * overrides both. Exported separately from the plugin so the XML manipulation
 * can be tested without running a prebuild.
 */
function setMlKitVisionDependencies(androidManifest, value = ML_KIT_DEPENDENCIES) {
  // tools:replace is only legal if the namespace is declared on <manifest>.
  // Expo's template already declares it; this keeps the plugin correct if that
  // ever changes.
  androidManifest.manifest.$ = androidManifest.manifest.$ ?? {};
  androidManifest.manifest.$['xmlns:tools'] = androidManifest.manifest.$['xmlns:tools'] ?? TOOLS_NAMESPACE;

  const application = AndroidConfig.Manifest.getMainApplicationOrThrow(androidManifest);
  application['meta-data'] = application['meta-data'] ?? [];

  const attributes = {
    'android:name': META_DATA_NAME,
    'android:value': value,
    'tools:replace': 'android:value',
  };

  const existing = application['meta-data'].find(
    item => item?.$?.['android:name'] === META_DATA_NAME
  );

  if (existing) {
    existing.$ = { ...existing.$, ...attributes };
  } else {
    application['meta-data'].push({ $: attributes });
  }

  return androidManifest;
}

const withMlKitVisionDependencies = config =>
  withAndroidManifest(config, innerConfig => {
    innerConfig.modResults = setMlKitVisionDependencies(innerConfig.modResults);
    return innerConfig;
  });

module.exports = withMlKitVisionDependencies;
module.exports.setMlKitVisionDependencies = setMlKitVisionDependencies;
module.exports.ML_KIT_DEPENDENCIES = ML_KIT_DEPENDENCIES;
module.exports.META_DATA_NAME = META_DATA_NAME;
