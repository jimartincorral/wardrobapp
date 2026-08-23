import { execFileSync } from 'node:child_process';
import { describe, expect, it } from 'vitest';
import { addReleaseSigning } from './withReleaseSigning';

/**
 * The signing rewrite, run against the real prebuild template.
 *
 * Read straight out of `node_modules/expo/template.tgz` rather than from a copied
 * snippet, because the whole risk here is the template changing shape underneath
 * the plugin: a snippet would keep passing while the actual build reverted to
 * debug signing. If an Expo bump moves these lines, this fails and says so.
 */
const template = execFileSync(
  'tar',
  ['-xzOf', 'node_modules/expo/template.tgz', 'package/android/app/build.gradle'],
  { encoding: 'utf8' }
);

describe('the template this runs against', () => {
  it('is the one the plugin expects', () => {
    // Stated so a failure above reads as "the template moved" rather than as a
    // mysterious regex miss.
    expect(template).toContain('signingConfigs {');
    expect(template).toContain('// Caution! In production, you need to generate your own keystore file.');
  });

  it('signs release builds with the debug key, which is the thing being fixed', () => {
    const release = template.slice(template.indexOf('buildTypes {'));

    expect(release).toContain('signingConfig signingConfigs.debug');
  });
});

describe('rewiring release signing', () => {
  const rewired = addReleaseSigning(template);

  it('adds a release signing config', () => {
    expect(rewired).toContain('release {');
    expect(rewired).toContain('storeFile file(WARDROBAPP_STORE_FILE)');
    expect(rewired).toContain('storePassword WARDROBAPP_STORE_PASSWORD');
    expect(rewired).toContain('keyAlias WARDROBAPP_KEY_ALIAS');
    expect(rewired).toContain('keyPassword WARDROBAPP_KEY_PASSWORD');
  });

  it('points the release build type at it, falling back to debug', () => {
    expect(rewired).toContain(
      "signingConfig project.hasProperty('WARDROBAPP_STORE_FILE') ? signingConfigs.release : signingConfigs.debug"
    );
  });

  it('leaves the debug build type alone', () => {
    // `signingConfig signingConfigs.debug` appears twice in the template. Editing
    // the debug one instead would leave release builds debug-signed while looking
    // correct, which is the failure this plugin exists to prevent.
    const debugBlock = rewired.slice(
      rewired.indexOf('buildTypes {'),
      rewired.indexOf('// Caution! In production')
    );

    expect(debugBlock).toContain('signingConfig signingConfigs.debug');
    expect(debugBlock).not.toContain('hasProperty');
  });

  it('changes the release signing line exactly once', () => {
    const occurrences = rewired.split('signingConfigs.release').length - 1;

    // Once in the buildTypes conditional. The signingConfigs block declares
    // `release {` rather than referring to `signingConfigs.release`.
    expect(occurrences).toBe(1);
  });

  it('keeps the rest of the release build type', () => {
    // The replacement is one line, not the block: minification, resource shrinking
    // and PNG crunching all live below it.
    expect(rewired).toContain('minifyEnabled enableMinifyInReleaseBuilds');
    expect(rewired).toContain('proguardFiles getDefaultProguardFile');
  });

  it('is idempotent', () => {
    // Prebuild re-applies mods over an existing android/, so this runs against a
    // file it has already edited. Appending a second time would produce a Gradle
    // file with two release configs and fail the build.
    expect(addReleaseSigning(rewired)).toBe(rewired);
  });
});

describe('when the template has moved', () => {
  it('refuses rather than quietly leaving debug signing in place', () => {
    // The important failure mode. A no-op here produces a release APK signed with
    // the public debug key that is indistinguishable from a correct one, so it has
    // to be loud.
    const withoutAnchor = template.replace(
      '// Caution! In production, you need to generate your own keystore file.',
      '// (comment reworded upstream)'
    );

    expect(() => addReleaseSigning(withoutAnchor)).toThrow(/template has changed shape/);
  });

  it('refuses when there is nowhere to put the signing config', () => {
    const withoutConfigs = template.replace('signingConfigs {', 'somethingElse {');

    expect(() => addReleaseSigning(withoutConfigs)).toThrow(/signingConfigs/);
  });
});
