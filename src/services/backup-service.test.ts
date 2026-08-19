import { describe, expect, it, vi } from 'vitest';

// These tests cover the pure helpers that decide how an archive is read, so the
// native surface is stubbed away at import time. expo-file-system/legacy and
// react-native-zip-archive need no stub: backup-service reaches them through
// lazy require()s that these helpers never trigger.
vi.mock('expo-file-system', () => ({
  Directory: class {},
  File: class {},
  Paths: { document: {}, cache: {} },
}));
vi.mock('@react-native-async-storage/async-storage', () => ({
  default: { getItem: vi.fn(), setItem: vi.fn(), removeItem: vi.fn() },
}));
vi.mock('../db/client', () => ({
  getDatabase: vi.fn(),
  closeDatabase: vi.fn(),
}));

const {
  checkArchiveCompleteness,
  checkLegacyPayload,
  classifyArchiveEntries,
  parseArchiveManifest,
  toNativePath,
} = await import('./backup-service');

/** The manifest the current app writes, as the shape the parser returns. */
const currentManifest = { version: 3, created_at: '2026-08-19T00:00:00.000Z', image_count: 2 };

describe('toNativePath', () => {
  it('strips the file:// scheme the zip module does not understand', () => {
    expect(toNativePath('file:///data/user/0/app/cache/backup-work')).toBe(
      '/data/user/0/app/cache/backup-work'
    );
  });

  it('decodes percent-escapes so paths with spaces still resolve', () => {
    expect(toNativePath('file:///storage/My%20Backups/wardrobe.zip')).toBe(
      '/storage/My Backups/wardrobe.zip'
    );
  });

  it('leaves non-file URIs alone', () => {
    expect(toNativePath('content://com.android.providers/document/1234')).toBe(
      'content://com.android.providers/document/1234'
    );
  });
});

describe('classifyArchiveEntries', () => {
  it('recognizes the current layout by its manifest', () => {
    expect(
      classifyArchiveEntries(['manifest.json', 'wardrobapp.db'], ['images'])
    ).toBe('folder');
  });

  it('recognizes a legacy v1/v2 archive by its embedded payload', () => {
    expect(classifyArchiveEntries(['backup.json'], ['images'])).toBe('legacy-archive');
  });

  it('prefers the manifest when a legacy payload is also present', () => {
    expect(
      classifyArchiveEntries(['manifest.json', 'backup.json'], [])
    ).toBe('folder');
  });

  it('reports a single wrapping directory so the caller can descend into it', () => {
    // Some zip implementations nest the source folder inside the archive.
    expect(classifyArchiveEntries([], ['wardrobapp-backup-2026-08-18'])).toBe('nested');
  });

  it('does not guess when several directories sit at the top level', () => {
    expect(classifyArchiveEntries([], ['images', 'something-else'])).toBe('unknown');
  });

  it('reports an archive with nothing recognizable as unknown', () => {
    expect(classifyArchiveEntries(['readme.txt'], [])).toBe('unknown');
  });
});

describe('parseArchiveManifest', () => {
  it('accepts the manifest the current app writes', () => {
    expect(parseArchiveManifest(JSON.stringify(currentManifest))).toEqual(currentManifest);
  });

  it('treats a missing image_count as unknown rather than zero', () => {
    // Reconciliation must not conclude "0 expected" and wave a truncated archive through.
    expect(parseArchiveManifest(JSON.stringify({ version: 3 })).image_count).toBeUndefined();
  });

  it('tells the user to update the app when the backup is from a newer version', () => {
    expect(() => parseArchiveManifest(JSON.stringify({ version: 4 }))).toThrow(/Update the app/);
  });

  it('names both versions when the format is too old to read', () => {
    expect(() => parseArchiveManifest(JSON.stringify({ version: 2 }))).toThrow(
      /format 2.*reads 3/
    );
  });

  it('rejects a manifest that is not readable JSON', () => {
    expect(() => parseArchiveManifest('{ truncated')).toThrow(/not readable JSON/);
  });

  it('rejects JSON that is not an object', () => {
    expect(() => parseArchiveManifest('[]')).toThrow(/does not describe a backup/);
    expect(() => parseArchiveManifest('null')).toThrow(/does not describe a backup/);
  });

  it('rejects a version that is absent or not an integer', () => {
    expect(() => parseArchiveManifest('{}')).toThrow(/no version number/);
    expect(() => parseArchiveManifest('{"version":"3"}')).toThrow(/no version number/);
    expect(() => parseArchiveManifest('{"version":3.5}')).toThrow(/no version number/);
  });
});

describe('checkArchiveCompleteness', () => {
  it('accepts an archive whose photo count matches its manifest', () => {
    expect(() =>
      checkArchiveCompleteness({ manifest: currentManifest, hasDatabase: true, imageCount: 2 })
    ).not.toThrow();
  });

  it('refuses an archive with no database, so photos are never wiped for nothing', () => {
    // The bug this guards: the photo wipe used to be unconditional while
    // restoring the database was not, so a database-less archive deleted every
    // photo and reported success.
    expect(() =>
      checkArchiveCompleteness({ manifest: currentManifest, hasDatabase: false, imageCount: 2 })
    ).toThrow(/wardrobapp\.db is missing.*Nothing was changed/s);
  });

  it('refuses a truncated archive that is missing photos', () => {
    expect(() =>
      checkArchiveCompleteness({ manifest: currentManifest, hasDatabase: true, imageCount: 1 })
    ).toThrow(/lists 2 photo\(s\) but only 1/);
  });

  it('accepts extra photos, since only a shortfall means truncation', () => {
    expect(() =>
      checkArchiveCompleteness({ manifest: currentManifest, hasDatabase: true, imageCount: 3 })
    ).not.toThrow();
  });

  it('skips reconciliation when the manifest carries no count', () => {
    expect(() =>
      checkArchiveCompleteness({
        manifest: { version: 3 },
        hasDatabase: true,
        imageCount: 0,
      })
    ).not.toThrow();
  });
});

describe('checkLegacyPayload', () => {
  it('accepts the v1 and v2 payloads still in the wild', () => {
    expect(() => checkLegacyPayload({ version: 1, database: 'base64' })).not.toThrow();
    expect(() => checkLegacyPayload({ version: 2, database: 'base64' })).not.toThrow();
  });

  it('refuses a legacy payload carrying no database', () => {
    expect(() => checkLegacyPayload({ version: 2, database: '' })).toThrow(
      /no database.*Nothing was changed/s
    );
  });

  it('names the formats it can read when the version is unknown', () => {
    expect(() => checkLegacyPayload({ version: 9, database: 'base64' })).toThrow(
      /format 9.*reads 1, 2 and 3/
    );
  });
});
