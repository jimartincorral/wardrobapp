# Wardrobapp

A local-first wardrobe and outfit planner for **Android**, built with React Native + Expo. Catalog the clothes you own, rate outfit suggestions, and let the app learn what you like — all stored on-device, with no account and no server.

> Status: pre-1.0, actively developed. The roadmap lives in [TODO.md](TODO.md).

## Features

- **Garment catalog** — photos, category and type, colour palette, tags, brand, size. Photos are resized to 800px and re-encoded at 70% JPEG on import to keep the database and backups small.
- **On-device background removal** — strips the background from a garment photo via [`@six33/react-native-bg-removal`](https://www.npmjs.com/package/@six33/react-native-bg-removal). Needs the native module linked, so a development or release build rather than Expo Go.
- **Duplicate detection** — when you add a garment, likely duplicates in the same category are flagged by a weighted average of tag overlap (Jaccard, 0.6), colour similarity (0.3) and size match (0.1). Signals with nothing to compare abstain rather than scoring zero, so an untagged garment can still be recognised as a duplicate.
- **Outfit suggestions** — an epsilon-greedy engine combining category templates, colour harmony judged by hue, season and occasion fit, and pair scores learned from your ratings.
- **Wardrobe analytics** — breakdowns by category, subcategory, colour and brand, plus garment lifespan for items you've marked unavailable.
- **Backup and restore** — a single `.zip` containing the SQLite database and every photo, written to a folder you pick. Restore stages and verifies the archive before replacing anything, and rolls back if it can't finish.
- **English and Spanish** — full UI localization, selectable in Settings.

## Tech stack

- **Runtime:** React Native 0.83, Expo SDK 55, React 19
- **Navigation:** expo-router (file-based, typed routes)
- **Storage:** expo-sqlite in WAL mode; photos as files under `<documents>/garment-images/`, referenced from the database by filename
- **State:** React context (theme, language) plus local hooks — no global store
- **Images:** expo-image-picker + expo-image-manipulator
- **Language:** TypeScript, `strict` mode

## Getting started

### Prerequisites

- Node.js 20+ and npm
- Android Studio with the Android SDK, plus JDK 17

### Install and run

```bash
git clone https://github.com/jimartincorral/wardrobapp.git
cd wardrobapp
npm install

# Build and run on a connected device or emulator
npm run android

# Or start the dev server and connect an existing build
npm start
```

The first launch creates the SQLite schema automatically.

> Expo Go will run most of the app, but anything backed by a native module — background removal, and backup/restore via the Storage Access Framework — needs `npm run android`.

### Build a release APK

CI builds one on every push to `main` and publishes it to the rolling [`nightly` release](https://github.com/jimartincorral/wardrobapp/releases/tag/nightly). Note that it is **signed with the public Android debug key**, so it is for testing rather than distribution.

To build locally, the portable path is what CI runs:

```bash
npx expo prebuild --platform android --no-install
cd android && ./gradlew assembleRelease
```

The output lands at `android/app/build/outputs/apk/release/app-release.apk`. There is also `npm run apk`, a PowerShell script that wraps the same steps and auto-detects a JDK 17 — Windows only.

## Project structure

```
app/                      expo-router screens
  (tabs)/                   index, wardrobe, outfits, analytics
  garment/                  add, [id] (detail), edit/[id]
  outfit/[id].tsx
  settings.tsx
  statistics.tsx
src/
  domain/                 Pure algorithms: outfit suggestion, duplicate
                          detection (no platform dependencies — see Architecture)
  db/                     SQLite client, schema, keyed data migrations
  services/               garment, outfit, analytics, image, backup,
                          background-removal, garment-analysis, url-import,
                          plus thin wiring for the domain algorithms
  components/             GarmentCard, GarmentForm, TagInput, ColorPicker,
                          OutfitPreview, RatingStars, DuplicateWarning, ...
  hooks/                  useGarments, useGarmentForm, useAnalytics, ...
  utils/                  colour distance (CIE76 ΔE), tag similarity (Jaccard),
                          image paths, garment fields, dates, style tags
  constants/  i18n/  theme/  types/
assets/                   App icon and splash
scripts/                  build-apk.ps1
```

## Architecture

Three layers, with a deliberate boundary between them:

- **`src/domain/`** — the algorithms: outfit suggestion and duplicate detection. No database, no filesystem, no clock, no React Native. Everything arrives as an argument, so a run is reproducible. `src/domain/purity.test.ts` walks the real import graph and fails if anything platform-bound creeps in, directly or transitively.
- **`src/utils/`, `src/constants/`, `src/types/`** — pure helpers the domain layer builds on (colour distance, tag similarity, occasion derivation, photo-reference handling).
- **`src/services/`, `src/db/`, `src/hooks/`, `app/`** — everything that talks to SQLite, the filesystem, the Storage Access Framework or the UI. The services that wrap a domain algorithm are thin: they load data, call the algorithm, and re-export its public API so callers see one module.

The split is deliberate: the domain layer is the part that would survive a rewrite of everything around it.

## Testing

```bash
npm run typecheck  # tsc --noEmit
npm test           # vitest, one-shot
npm run test:watch
```

18 suites, 175 tests, covering the suggestion engine, duplicate detection, colour comparison, backup validation, the database lock and migrations, URL import, garment and outfit services, the domain layer's dependency-freedom, and the pure utilities. Both `typecheck` and `test` run in CI on every pull request.

Domain algorithms are checked by mutation: each behaviour the tests claim to protect is removed in turn, and the intended test must fail. A test that passes without the code it covers is not a test.

## Limitations

- **Android only.** Web and iOS support were removed — the web build had its own storage layer that could silently lose data, and iOS was never finished.
- **No cloud sync**, by design. Backups are the way to move a wardrobe to another device.
- **Released APKs are debug-signed**, so they can't be upgraded in place from a properly signed build later.
- **The schema is applied idempotently** at startup from raw SQL in `src/db/client.ts` — `CREATE TABLE IF NOT EXISTS` plus additive `ALTER`s. There's no `PRAGMA user_version` yet. Keyed, run-once data migrations live in `src/db/migrations.ts`.
- **No wear log.** The app records outfit ratings, not what you wore on a given day, so there is no cost-per-wear or wear-trend reporting.

## Contributing

This is a personal project, but issues and PRs are welcome. For anything non-trivial, open an issue first so we can talk through scope. Pull requests must pass `typecheck` and `test`.

By contributing, you agree that your contributions are licensed under the project's AGPL-3.0 license.

## License

[AGPL-3.0](LICENSE) — GNU Affero General Public License v3.0.

In plain English: you can use, modify, and redistribute Wardrobapp freely, including running it as a hosted service, **provided that** you publish the full source of your modified version under the same AGPL-3.0 license. For closed-source or proprietary commercial use, contact the author to discuss a commercial license.
