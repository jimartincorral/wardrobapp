import { describe, expect, it, vi } from 'vitest';

// backup-service is native-only; these tests cover the pure helpers that decide
// how an archive is read, so the native surface is stubbed away at import time.
vi.mock('react-native', () => ({ Platform: { OS: 'android' } }));
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

const { classifyArchiveEntries, toNativePath } = await import('./backup-service');

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
