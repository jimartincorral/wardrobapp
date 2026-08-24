import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve, sep } from 'node:path';

/**
 * No user-facing English left in the screens.
 *
 * Both languages are shipped, and a missed string is invisible in the language
 * it was written in: it reads perfectly until someone switches to Spanish and
 * one label stays put. That is how `Pinned: ${name}` survived on the outfits
 * screen, with translated buttons directly underneath it.
 *
 * Two detectors, because the misses come in two shapes. [visibleSiteLiterals]
 * looks at the places text reaches a person -- JSX children, the props that are
 * shown, `Alert.alert` -- and catches a single word like "Save". [proseLiterals]
 * looks at shape instead of position, and catches a sentence built anywhere in a
 * screen, which is what a template literal in a ternary is.
 *
 * `t('...')` keys are removed before either detector runs, so a translated call
 * is invisible to both. `console.*` is left alone on purpose: its text is for
 * whoever is reading a log, not for whoever is holding the phone.
 *
 * Scope is `.tsx` -- the screens and components. Text thrown from `src/services`
 * is a separate hole, and a real one: `getErrorMessage(error)` puts an Error's
 * message straight into an Alert, so around forty English sentences in the
 * service layer can reach a Spanish reader. Fixing that means those functions
 * reporting a reason rather than a sentence, the way the Kotlin port's
 * `ImportFailureReason` does. It is tracked in TODO.md, not here, because a
 * check that fails for forty known reasons stops being read.
 */

const ROOTS = ['app', 'src'].map(dir => resolve(__dirname, '..', '..', dir));

/**
 * Text that is still English, and why it is still here.
 *
 * Every entry is a promise, not a decision. Empty is the finished state.
 */
/**
 * Text that is the same in both languages, and stays literal.
 *
 * A product name with a version in it is not a translator's problem, and
 * putting it behind a key would invite somebody to translate it.
 */
const NOT_TRANSLATABLE = new Set(['Expo SDK 55']);

const KNOWN_UNTRANSLATED = new Set([
  // The first line of the backup progress bar. The other six phases of that
  // same bar are emitted by `backup-service.ts`, so translating this one alone
  // would make the bar switch language halfway through a backup. It goes when
  // `BackupProgress` carries a step rather than a sentence.
  'Starting backup',
]);

function sourceFiles(dir: string): string[] {
  const found: string[] = [];

  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) found.push(...sourceFiles(path));
    else if (entry.name.endsWith('.tsx') && !entry.name.includes('.test.')) found.push(path);
  }

  return found.sort();
}

/** Source with everything that is not shipped text taken out. */
function readable(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/[^\n]*/g, '')
    .replace(/^\s*import[\s\S]*?from\s*['"][^'"]*['"];?\s*$/gm, '')
    // A translated call is not a literal any more. Removing the key rather than
    // the whole call keeps the surrounding expression intact, so a hardcoded
    // string sitting beside a translated one is still found.
    .replace(/\bt\(\s*(['"`])(?:[^'"`\\]|\\.)*\1/g, 't(')
    // Log text is written to be read by whoever is debugging, in whatever
    // language the code is in.
    .replace(/\bconsole\.\w+\([\s\S]*?\);/g, '');
}

/** Whether a literal is text a translator would have anything to do with. */
function isProse(literal: string): boolean {
  const withoutValues = literal
    .replace(/\$\{[^}]*\}/g, ' ')
    .replace(/%\{[^}]*\}/g, ' ')
    .replace(/\\u[0-9a-fA-F]{4}/g, '');

  // A word of two letters or more with whitespace against it. Short of that a
  // literal is more likely a style value, a route or a key: 'space-between' has
  // no space in it and '/garment/add' has no words, while 'Pinned: ' is a word
  // and a space once the name is taken out of it -- which is the shape the
  // outfits screen shipped.
  return /[A-Za-z]{2,}[.,:;!?]?\s|\s[A-Za-z]{2,}/.test(withoutValues);
}

function literalsIn(fragment: string): string[] {
  return [...fragment.matchAll(/(['"`])((?:[^\\\n]|\\.)*?)\1/g)].map(m => m[2]);
}

/** Sentences, wherever in a screen they were built. */
function proseLiterals(source: string): string[] {
  return literalsIn(readable(source)).filter(isProse);
}

/** Words at the call sites where text reaches a person, sentence or not. */
function visibleSiteLiterals(source: string): string[] {
  const code = readable(source);
  const found: string[] = [];

  // A JSX child, closed by a tag so that `Promise<void>` is not read as one.
  for (const m of code.matchAll(/>\s*([A-Za-z][^<>{}\n]*?)\s*<\//g)) found.push(m[1]);

  // The props that end up in front of somebody, quoted or braced.
  const shown = 'placeholder|title|label|accessibilityLabel|accessibilityHint';
  for (const m of code.matchAll(new RegExp(`\\b(?:${shown})\\s*=\\s*(?:\\{\\s*)?(['"\`])((?:[^\\\\\\n]|\\\\.)*?)\\1`, 'g'))) {
    found.push(m[2]);
  }

  // Both halves of an alert are read out of the dialog, as are its buttons.
  // `style` is not read out: 'cancel' and 'destructive' are how the dialog is
  // told to draw the button, not what it says on it.
  for (const m of code.matchAll(/\bAlert\.alert\(([\s\S]{0,600}?)\)\s*;/g)) {
    found.push(...literalsIn(m[1].replace(/\bstyle\s*:\s*(['"`])[^'"`]*\1/g, '')));
  }

  return found.filter(text => /[A-Za-z]{2,}/.test(text.replace(/\$\{[^}]*\}/g, ' ')));
}

function offendersIn(file: string): string[] {
  const source = readFileSync(file, 'utf8');
  const found = new Set([...proseLiterals(source), ...visibleSiteLiterals(source)]);

  return [...found].filter(
    text => !KNOWN_UNTRANSLATED.has(text.trim()) && !NOT_TRANSLATABLE.has(text.trim())
  );
}

describe('hardcoded strings', () => {
  const files = ROOTS.flatMap(sourceFiles);

  it('leaves no user-facing text outside the translations', () => {
    const offenders = files.flatMap(file =>
      offendersIn(file).map(text => `${file.split(sep).slice(-2).join(sep)}: "${text}"`)
    );

    expect(offenders).toEqual([]);
  });

  it('keeps the list of known misses honest', () => {
    // An entry that has been translated but left on the list would exempt that
    // wording from the check for good, which is how this kind of allowlist stops
    // meaning anything.
    const everything = files.map(file => readFileSync(file, 'utf8')).join('\n');
    const stale = [...KNOWN_UNTRANSLATED, ...NOT_TRANSLATABLE].filter(
      text => !everything.includes(text)
    );

    expect(stale).toEqual([]);
  });

  it('finds the shapes it claims to', () => {
    // Otherwise "no offenders" could mean the patterns match nothing at all,
    // which is the same result as success and reads identically.
    const source = [
      '<Text>Saved</Text>',
      '<TextInput placeholder="Brand" />',
      "<Pressable accessibilityLabel={'Remove photo'} />",
      "Alert.alert('Error', 'That did not work');",
      '<Text>{outfit.is_pinned ? `Pinned: ${outfit.name}` : outfit.name}</Text>',
      "setProgress({ message: 'Copying database' });",
    ].join('\n');

    expect(new Set([...proseLiterals(source), ...visibleSiteLiterals(source)])).toEqual(
      new Set([
        'Saved',
        'Brand',
        'Remove photo',
        'Error',
        'That did not work',
        'Pinned: ${outfit.name}',
        'Copying database',
      ])
    );
  });

  it('ignores what nobody reads', () => {
    const source = [
      "import { View } from 'react-native';",
      '// <Text>In a comment</Text>',
      '/* <Text>In a block comment</Text> */',
      "console.warn('Failed to load statistics:', error);",
      "<Text>{t('outfits.savedTitle')}</Text>",
      "Alert.alert(t('addGarment.errors.errorTitle'), t('addGarment.errors.saveFailed'));",
      "router.push('/garment/add');",
      "<View style={{ flexDirection: 'row', justifyContent: 'space-between' }} />",
      'async function load(): Promise<void> {}',
      "<Text>{`${count}%`}</Text>",
    ].join('\n');

    expect([...proseLiterals(source), ...visibleSiteLiterals(source)]).toEqual([]);
  });
});
