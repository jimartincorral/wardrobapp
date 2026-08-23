const { withAppBuildGradle } = require('expo/config-plugins');

/**
 * Sign release builds with a real key, when there is one.
 *
 * The prebuild template points `buildTypes.release` at the *debug* signing config
 * -- with a comment admitting it -- so a release APK built from it carries the
 * public Android debug key. This rewires it to a release config read from Gradle
 * properties, and falls back to the template's behaviour when those are absent.
 *
 * A plugin rather than a patch applied after prebuild, because `android/` is
 * generated on every build and anything written into it by hand is thrown away.
 * Expo does ship a `credentials.json` mechanism that does the same job, but the
 * function that installs it is called by the EAS CLI rather than by
 * `npx expo prebuild`, which is what this project runs.
 *
 * The fallback is the part that matters day to day: with no keystore configured,
 * `npm run android`, `npm run apk` and CI all behave exactly as before. Nobody
 * needs a secret to build the app.
 */

/** The Gradle property that decides whether there is a key to sign with. */
const STORE_FILE = 'WARDROBAPP_STORE_FILE';

/**
 * The line the template uses for the release build type, and the comment above it.
 *
 * Anchored on the comment because `signingConfig signingConfigs.debug` appears
 * twice -- the debug build type has it too -- and editing the wrong one would
 * leave release builds debug-signed while looking correct.
 */
const RELEASE_SIGNING_ANCHOR =
  /(\/\/ Caution! In production, you need to generate your own keystore file\.\n\s*\/\/ see https:\/\/reactnative\.dev\/docs\/signed-apk-android\.\n)(\s*)signingConfig signingConfigs\.debug/;

/** Where the template declares its signing configs, so the release one can join. */
const SIGNING_CONFIGS_ANCHOR = /(signingConfigs \{\n)/;

const RELEASE_SIGNING_CONFIG = `        release {
            // Populated only when a keystore is configured. Left empty otherwise
            // so the block exists and Gradle can resolve it either way.
            if (project.hasProperty('${STORE_FILE}')) {
                storeFile file(${STORE_FILE})
                storePassword WARDROBAPP_STORE_PASSWORD
                keyAlias WARDROBAPP_KEY_ALIAS
                keyPassword WARDROBAPP_KEY_PASSWORD
            }
        }
`;

function withReleaseSigning(config) {
  return withAppBuildGradle(config, gradleConfig => {
    gradleConfig.modResults.contents = addReleaseSigning(gradleConfig.modResults.contents);

    return gradleConfig;
  });
}

/**
 * Rewire release signing, or say why it could not.
 *
 * Throws rather than returning the file unchanged when an anchor is missing. A
 * silent no-op here would produce a "release" APK that is quietly debug-signed and
 * indistinguishable from a correct one -- which is the exact failure this exists to
 * end, so it has to be loud.
 */
function addReleaseSigning(contents) {
  // Prebuild re-applies mods over an existing android/, so this can run against a
  // file it has already edited.
  if (contents.includes(STORE_FILE)) return contents;

  if (!SIGNING_CONFIGS_ANCHOR.test(contents)) {
    throw new Error(
      'withReleaseSigning: no `signingConfigs {` block in the generated ' +
        'app/build.gradle. The Expo template has changed shape; update the plugin ' +
        'rather than shipping release builds signed with the debug key.'
    );
  }

  if (!RELEASE_SIGNING_ANCHOR.test(contents)) {
    throw new Error(
      'withReleaseSigning: could not find the release build type\'s signing line ' +
        'in the generated app/build.gradle. The Expo template has changed shape; ' +
        'update the plugin rather than shipping release builds signed with the ' +
        'debug key.'
    );
  }

  return contents
    .replace(SIGNING_CONFIGS_ANCHOR, `$1${RELEASE_SIGNING_CONFIG}`)
    .replace(
      RELEASE_SIGNING_ANCHOR,
      `$1$2signingConfig project.hasProperty('${STORE_FILE}') ? signingConfigs.release : signingConfigs.debug`
    );
}

module.exports = withReleaseSigning;
module.exports.addReleaseSigning = addReleaseSigning;
