import { afterAll, beforeEach, describe, expect, it } from 'vitest';

import appJson from './app.json';

// eslint-disable-next-line @typescript-eslint/no-var-requires
const appConfig = require('./app.config.js') as (input: {
  config: Record<string, unknown>;
}) => Record<string, any>;

/**
 * The two computed parts of the Expo config.
 *
 * Worth testing rather than eyeballing, because both failures are silent. A
 * versionCode that does not increase produces an APK Android refuses to install
 * as an upgrade -- which is what shipped until now, every build being version 1 --
 * and a dropped plugin entry produces a "release" APK signed with the debug key
 * that looks entirely fine.
 */

const base = () => ({ config: JSON.parse(JSON.stringify(appJson.expo)) });

// Cleared before every test, not merely after: GITHUB_RUN_NUMBER is already set
// when this suite runs on CI, so a test that means "no run number" has to say so
// rather than inherit one. Restored afterwards so nothing else in the process
// sees a doctored environment.
const ambientRunNumber = process.env.GITHUB_RUN_NUMBER;

beforeEach(() => {
  delete process.env.GITHUB_RUN_NUMBER;
});

afterAll(() => {
  if (ambientRunNumber === undefined) {
    delete process.env.GITHUB_RUN_NUMBER;
  } else {
    process.env.GITHUB_RUN_NUMBER = ambientRunNumber;
  }
});

describe('appConfig', () => {
  it('gives a local build the offset itself', () => {
    expect(appConfig(base()).android.versionCode).toBe(1000);
  });

  it('adds the CI run number to the offset', () => {
    process.env.GITHUB_RUN_NUMBER = '42';
    expect(appConfig(base()).android.versionCode).toBe(1042);
  });

  it('increases with the run number', () => {
    process.env.GITHUB_RUN_NUMBER = '7';
    const earlier = appConfig(base()).android.versionCode;
    process.env.GITHUB_RUN_NUMBER = '8';
    expect(appConfig(base()).android.versionCode).toBeGreaterThan(earlier);
  });

  it('falls back to the offset rather than NaN when the run number is not a number', () => {
    process.env.GITHUB_RUN_NUMBER = 'not-a-number';
    expect(appConfig(base()).android.versionCode).toBe(1000);
  });

  it('keeps the rest of the android config from app.json', () => {
    const android = appConfig(base()).android;
    expect(android.package).toBe(appJson.expo.android.package);
    // Whatever else app.json declares there has to survive: overriding one field
    // by rebuilding the object is exactly how the others get lost.
    for (const key of Object.keys(appJson.expo.android)) {
      expect(android).toHaveProperty(key);
    }
  });

  it('keeps the top-level config from app.json', () => {
    const config = appConfig(base());
    expect(config.name).toBe(appJson.expo.name);
    expect(config.scheme).toBe(appJson.expo.scheme);
  });

  it('adds the signing plugin without dropping the ones app.json declares', () => {
    const plugins = appConfig(base()).plugins;
    for (const plugin of appJson.expo.plugins) {
      expect(plugins).toContainEqual(plugin);
    }
    expect(plugins).toContain('./plugins/withReleaseSigning');
  });

  it('adds the signing plugin to a config that declares none', () => {
    expect(appConfig({ config: {} }).plugins).toEqual(['./plugins/withReleaseSigning']);
  });
});
