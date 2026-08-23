import { describe, expect, it } from 'vitest';
import {
  isLegacyAbsoluteImageRef,
  resolveImageRef,
  toStoredImageRef,
  isStoredGarmentImage,
  orphanedImageRefs,
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
    // Inline data contains slashes, so a naive basename would shred it.
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

describe('telling a stored photo from a temporary one', () => {
  // What keeps a cleanup from deleting live data: the native background-removal
  // module leaves an intermediate behind that should go, but the same reference
  // can be an already-stored cut-out that must not.
  const directory = 'file:///data/user/0/com.anonymous.wardrobapp/files/garment-images/';

  it('recognises a photo in the garment images directory', () => {
    expect(isStoredGarmentImage(`${directory}abc_nobg.png`, directory)).toBe(true);
    expect(isStoredGarmentImage(`${directory}abc.jpg`, directory)).toBe(true);
  });

  it('does not recognise the background-removal module intermediate', () => {
    // Written to the app's files root, one level above the photos, and nothing
    // else ever deletes it.
    expect(
      isStoredGarmentImage('file:///data/user/0/com.anonymous.wardrobapp/files/front.png', directory)
    ).toBe(false);
  });

  it('does not recognise a cache file', () => {
    expect(
      isStoredGarmentImage('file:///data/user/0/com.anonymous.wardrobapp/cache/x.jpg', directory)
    ).toBe(false);
  });

  it('does not recognise a path that merely mentions the folder', () => {
    // Matching the name anywhere would let a temp file through, and a false
    // positive here is a file that is never cleaned up.
    expect(isStoredGarmentImage('file:///tmp/garment-images/x.jpg', directory)).toBe(false);
    expect(isStoredGarmentImage('file:///data/garment-images-old/x.jpg', directory)).toBe(false);
  });

  it('does not recognise something nested deeper', () => {
    // Photos are stored flat, so a nested path is not one of ours.
    expect(isStoredGarmentImage(`${directory}nested/x.jpg`, directory)).toBe(false);
  });

  it('does not recognise a remote or content reference', () => {
    expect(isStoredGarmentImage('https://example.com/x.jpg', directory)).toBe(false);
    expect(isStoredGarmentImage('content://media/external/images/1', directory)).toBe(false);
  });

  it('requires the reference to begin at the directory, not merely contain it', () => {
    // A gate on deletion should not be satisfied by a path that happens to have
    // the directory somewhere inside it.
    expect(isStoredGarmentImage(`file:///tmp/staged/${directory}abc.jpg`, directory)).toBe(false);
  });

  it('says no when it has nothing to compare against', () => {
    // Before the filesystem is available the directory is empty, and then nothing
    // can be established either way -- so nothing gets deleted. The bare filename
    // is the case that matters: that is the form the *database* stores, so an
    // empty directory must not make one look like a file on disk.
    expect(isStoredGarmentImage(`${directory}abc.jpg`, '')).toBe(false);
    expect(isStoredGarmentImage('abc.jpg', '')).toBe(false);
    expect(isStoredGarmentImage('abc_nobg.png', '')).toBe(false);
    expect(isStoredGarmentImage('', directory)).toBe(false);
  });
});

describe('what a garment no longer references', () => {
  it('names a photo that was dropped', () => {
    expect(orphanedImageRefs(['a.jpg', 'b.jpg'], ['a.jpg'])).toEqual(['b.jpg']);
  });

  it('names nothing when everything is still in use', () => {
    expect(orphanedImageRefs(['a.jpg', 'b.jpg'], ['b.jpg', 'a.jpg'])).toEqual([]);
  });

  it('matches on the stored filename, whatever form the reference is in', () => {
    // The same photo is named differently depending on where it came from: a
    // resolved URI from a read, a bare filename from the database, an absolute
    // path from an older build. Comparing the strings as given would report a
    // file that is still in use, and it would be deleted.
    const previous = [`${DIR}a.jpg`, `${OLD_DIR}b.jpg`, 'c.jpg'];

    expect(orphanedImageRefs(previous, ['a.jpg', `${DIR}b.jpg`, `${DIR}c.jpg`])).toEqual([]);
  });

  it('names a dropped photo even when the two sides name things differently', () => {
    expect(orphanedImageRefs([`${DIR}a.jpg`, `${OLD_DIR}gone.jpg`], ['a.jpg']))
      .toEqual([`${OLD_DIR}gone.jpg`]);
  });

  it('names a cut-out that was undone', () => {
    // The collapse case: the garment held a cut-out in both columns, the edit put
    // the original back, so the cut-out is now unreferenced.
    expect(orphanedImageRefs(['a-cut.png', 'a-cut.png'], ['a.jpg', '']))
      .toEqual(['a-cut.png']);
  });

  it('names each file once, however many times it was referenced', () => {
    // A collapsed garment holds the same file in both columns, so a naive pass
    // would return it twice and the second delete would fail.
    expect(orphanedImageRefs(['gone.png', 'gone.png', `${DIR}gone.png`], []))
      .toEqual(['gone.png']);
  });

  it('ignores blanks on either side', () => {
    // The cut-out column is '' where a photo has none, and '' is not a file.
    expect(orphanedImageRefs(['', 'a.jpg', ''], ['', ''])).toEqual(['a.jpg']);
    expect(orphanedImageRefs([], ['a.jpg'])).toEqual([]);
  });

  it('names everything when a garment is emptied', () => {
    expect(orphanedImageRefs(['a.jpg', 'b.jpg'], [])).toEqual(['a.jpg', 'b.jpg']);
  });
});
