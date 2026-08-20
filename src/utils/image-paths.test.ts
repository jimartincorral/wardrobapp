import { describe, expect, it } from 'vitest';
import {
  isLegacyAbsoluteImageRef,
  resolveImageRef,
  toStoredImageRef,
} from './image-paths';

const DIR = 'file:///var/mobile/Containers/Data/Application/NEW/Documents/garment-images/';
const OLD_DIR = 'file:///var/mobile/Containers/Data/Application/OLD/Documents/garment-images/';

describe('toStoredImageRef', () => {
  it('reduces an absolute path to its filename', () => {
    expect(toStoredImageRef(`${OLD_DIR}front.jpg`)).toBe('front.jpg');
  });

  it('leaves a bare filename alone', () => {
    expect(toStoredImageRef('front.jpg')).toBe('front.jpg');
  });

  it('preserves references that are already portable', () => {
    // Reducing any of these to a "filename" would destroy the reference.
    const dataUri = 'data:image/jpeg;base64,AAAA';
    expect(toStoredImageRef(dataUri)).toBe(dataUri);
    expect(toStoredImageRef('blob:http://localhost/abc-123')).toBe('blob:http://localhost/abc-123');
    expect(toStoredImageRef('https://example.com/a/b/shirt.jpg')).toBe(
      'https://example.com/a/b/shirt.jpg'
    );
    expect(toStoredImageRef('content://provider/document/42')).toBe(
      'content://provider/document/42'
    );
  });

  it('maps an empty reference to an empty string', () => {
    expect(toStoredImageRef('')).toBe('');
  });
});

describe('resolveImageRef', () => {
  it('attaches the current directory to a bare filename', () => {
    expect(resolveImageRef('front.jpg', DIR)).toBe(`${DIR}front.jpg`);
  });

  it('re-bases a stale absolute path onto the current directory', () => {
    // The whole point: the file was restored correctly, but the container UUID
    // in the stored path no longer exists.
    expect(resolveImageRef(`${OLD_DIR}front.jpg`, DIR)).toBe(`${DIR}front.jpg`);
  });

  it('passes portable references through untouched', () => {
    const dataUri = 'data:image/jpeg;base64,AAAA';
    expect(resolveImageRef(dataUri, DIR)).toBe(dataUri);
    expect(resolveImageRef('https://example.com/a/shirt.jpg', DIR)).toBe(
      'https://example.com/a/shirt.jpg'
    );
  });

  it('returns the reference unchanged when there is no directory', () => {
    // Web, and anywhere the filesystem is unavailable: better to hand back what
    // was stored than to resolve it against nothing.
    expect(resolveImageRef('front.jpg', '')).toBe('front.jpg');
  });

  it('maps an empty reference to an empty string', () => {
    expect(resolveImageRef('', DIR)).toBe('');
  });
});

describe('store/resolve round trip', () => {
  it('survives a move to a different install directory', () => {
    const original = `${OLD_DIR}front.jpg`;
    const stored = toStoredImageRef(original);
    expect(resolveImageRef(stored, DIR)).toBe(`${DIR}front.jpg`);
  });

  it('is stable when applied repeatedly', () => {
    // Reads resolve and writes reduce, so a value can round-trip many times.
    let value = `${OLD_DIR}front.jpg`;
    for (let i = 0; i < 3; i++) {
      value = resolveImageRef(toStoredImageRef(value), DIR);
    }
    expect(value).toBe(`${DIR}front.jpg`);
  });

  it('does not corrupt a data URI across a round trip', () => {
    // Data URIs contain slashes, so a naive basename would shred them.
    const dataUri = 'data:image/jpeg;base64,AAA/BBB';
    expect(resolveImageRef(toStoredImageRef(dataUri), DIR)).toBe(dataUri);
  });
});

describe('isLegacyAbsoluteImageRef', () => {
  it('identifies rows the migration still needs to rewrite', () => {
    expect(isLegacyAbsoluteImageRef(`${OLD_DIR}front.jpg`)).toBe(true);
    expect(isLegacyAbsoluteImageRef('front.jpg')).toBe(false);
    expect(isLegacyAbsoluteImageRef('data:image/jpeg;base64,AAA/BBB')).toBe(false);
    expect(isLegacyAbsoluteImageRef('')).toBe(false);
  });
});
