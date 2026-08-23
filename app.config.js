/**
 * The Expo config, on top of app.json.
 *
 * app.json stays the base -- Expo reads it first and passes it in here -- so this
 * file only holds the parts that have to be computed. Two of them.
 */

/**
 * Base for the version code, on top of the CI run number.
 *
 * A version code has to increase for Android to accept a build as an upgrade of
 * the one installed, and it can never go back down. `GITHUB_RUN_NUMBER` counts up
 * on its own, but it restarts at 1 if the workflow is ever deleted and recreated --
 * after which every build would be refused by every phone that had a later one.
 *
 * So it is offset by a number kept here on purpose. If the run number ever does
 * reset, raise this past the highest version code already published and the
 * sequence carries on.
 *
 * Currently 1000 with nothing published above it: until now no build set a version
 * code at all, so every APK ever released has been version 1.
 */
const VERSION_CODE_OFFSET = 1000;

/**
 * Local builds all come out at the offset itself.
 *
 * They are not upgrading anything -- a local build is installed over whatever was
 * there by `adb install -r`, which does not care -- and giving them a number that
 * drifted with the clock would make it harder to tell which build a phone has.
 */
function versionCode() {
  // Parsed rather than trusted: a run number that is not a number would make this
  // NaN, and prebuild would happily write `versionCode NaN` into build.gradle.
  const run = Number.parseInt(process.env.GITHUB_RUN_NUMBER ?? '', 10);
  return VERSION_CODE_OFFSET + (Number.isFinite(run) && run > 0 ? run : 0);
}

module.exports = ({ config }) => ({
  ...config,
  android: {
    ...config.android,
    versionCode: versionCode(),
  },
  // Release signing, which the prebuild template otherwise wires to the *debug*
  // key. Appended rather than replacing, so the plugin list in app.json stays the
  // one place plugins are declared.
  plugins: [...(config.plugins ?? []), './plugins/withReleaseSigning'],
});
