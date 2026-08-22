import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';

/**
 * The domain layer is the part of the app that ports to another platform
 * unchanged, so it must not reach for the database, the filesystem, the clock
 * or React Native — directly or through anything it imports.
 *
 * This walks the real import graph rather than trusting the files to stay clean.
 */

const DOMAIN_DIR = resolve(__dirname);
const SRC_DIR = resolve(__dirname, '..');

/** Third-party packages the domain layer may depend on: pure computation only. */
const ALLOWED_PACKAGES = new Set(['date-fns']);

/** Modules that make a file platform-bound rather than portable. */
const FORBIDDEN = [
  'react-native',
  'react',
  'expo',
  '@react-native',
  '@expo',
  '@react-navigation',
];

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true })
    .filter(e => e.isFile() && /\.tsx?$/.test(e.name) && !e.name.includes('.test.'))
    .map(e => join(dir, e.name));
}

function importsOf(file: string): string[] {
  const source = readFileSync(file, 'utf8');
  return [...source.matchAll(/(?:from|import)\s*\(?\s*['"]([^'"]+)['"]/g)].map(m => m[1]);
}

function resolveRelative(fromFile: string, spec: string): string | null {
  const base = resolve(dirname(fromFile), spec);
  for (const candidate of [`${base}.ts`, `${base}.tsx`, join(base, 'index.ts')]) {
    if (existsSync(candidate)) return candidate;
  }
  return null;
}

/** Every file the domain layer pulls in, transitively. */
function domainClosure(): { files: Set<string>; packages: Set<string> } {
  const files = new Set<string>();
  const packages = new Set<string>();
  const queue = sourceFiles(DOMAIN_DIR);

  while (queue.length > 0) {
    const file = queue.pop()!;
    if (files.has(file)) continue;
    files.add(file);

    for (const spec of importsOf(file)) {
      if (spec.startsWith('.')) {
        const target = resolveRelative(file, spec);
        if (target && !files.has(target)) queue.push(target);
      } else {
        packages.add(spec);
      }
    }
  }

  return { files, packages };
}

describe('domain layer purity', () => {
  const { files, packages } = domainClosure();

  it('pulls in only pure third-party packages', () => {
    const offenders = [...packages].filter(
      pkg => !ALLOWED_PACKAGES.has(pkg) && FORBIDDEN.some(bad => pkg === bad || pkg.startsWith(`${bad}/`))
    );
    expect(offenders).toEqual([]);
  });

  it('declares every third-party package it uses', () => {
    // Keeps the allowlist honest: a new dependency has to be considered, not
    // silently inherited.
    expect([...packages].filter(pkg => !ALLOWED_PACKAGES.has(pkg))).toEqual([]);
  });

  it('never reaches into the database or service layers', () => {
    const offenders = [...files]
      .map(f => f.slice(SRC_DIR.length + 1))
      .filter(rel => rel.startsWith('db/') || rel.startsWith('services/'));
    expect(offenders).toEqual([]);
  });

  it('covers a real graph rather than silently finding nothing', () => {
    // Guards the guard: if resolution broke, the checks above would pass empty.
    expect(files.size).toBeGreaterThan(3);
    expect([...files].some(f => f.includes('outfit-suggestions'))).toBe(true);
    expect([...files].some(f => f.includes('utils/color-distance'))).toBe(true);
  });
});
