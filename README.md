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
  domain/                 Pure logic: outfit suggestion, duplicate detection,
                          pair learning, list filtering, form state, backup
                          validation (no platform dependencies — see Architecture)
  db/                     SQLite client, schema, keyed data migrations
  services/               garment, outfit, analytics, image, backup,
                          background-removal, garment-analysis, url-import,
                          plus thin wiring for the domain algorithms
  components/             GarmentCard, GarmentForm, TagInput, ColorPicker,
                          OutfitPreview, RatingStars, DuplicateWarning, ...
  hooks/                  useGarments, useGarmentForm, useAnalytics, ...
  utils/                  colour distance (CIE76 ΔE), tag similarity (Jaccard),
                          image paths, garment fields, dates, style tags,
                          which URLs are safe to fetch
  constants/  i18n/  theme/  types/
assets/                   App icon and splash
scripts/                  build-apk.ps1, dump-domain-parity.ts
native/                   The Kotlin/Android port (see Architecture)
  domain/                   Ported algorithms, plain Kotlin/JVM — no Android SDK
                            needed to build or test
  data/                     Row and photo-reference mapping into domain types
  presentation/             List filtering and ordering, form state — pure
  parity-testing/           Shared fixture loading for the parity suites
```

## Architecture

Three layers, with a deliberate boundary between them:

- **`src/domain/`** — the logic that does not need a platform: outfit suggestion, duplicate detection, how a rating folds into a garment pair's learned score, how the wardrobe list is filtered and ordered, how the garment form moves between states, and whether a backup archive can be restored. No database, no filesystem, no clock, no React Native. Everything arrives as an argument, so a run is reproducible. `src/domain/purity.test.ts` walks the real import graph and fails if anything platform-bound creeps in, directly or transitively.
- **`src/utils/`, `src/constants/`, `src/types/`** — pure helpers the domain layer builds on (colour distance, tag similarity, occasion derivation, photo-reference handling).
- **`src/services/`, `src/db/`, `src/hooks/`, `app/`** — everything that talks to SQLite, the filesystem, the Storage Access Framework or the UI. The services that wrap a domain algorithm are thin: they load data, call the algorithm, and re-export its public API so callers see one module.

The split is deliberate: the domain layer is the part that would survive a rewrite of everything around it — which is now underway.

### The native port

`native/` holds the Kotlin port, the first phase of moving this app to native Android. Every module there is a plain Kotlin/JVM one, deliberately *not* an Android module, so the whole thing builds and tests with nothing but a JDK:

```bash
cd native && ./gradlew test
```

| Module | What |
|---|---|
| `:domain` | The algorithms — colour, tags, occasions, duplicates, suggestions — and the garment vocabulary they key on |
| `:data` | The database — row and photo-reference mapping, reads, writes, analytics, suggestion loading, duplicate candidates, photo storage rules, backup restore |
| `:parity-testing` | Shared fixture-loading for the parity suites |
| `:presentation` | What the screens show — filtering, ordering, form state, a garment's detail, the outfit filters, the analytics bars, as pure functions |
| `:app` | The Compose UI and the platform plumbing — **only included when an Android SDK is present** |

`:app` is the one module that genuinely needs the Android SDK, so `settings.gradle.kts` includes it only when one is found (`ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`). `./gradlew test` therefore works on a machine with nothing but a JDK — which is the whole reason the other modules are pure — while CI builds everything. Keeping the pure parts pure is what lets everything so far be verified on any machine — and `:data` is the code that decides whether an *existing* wardrobe opens correctly, so it is the code most worth being able to test anywhere.

The port also differs from the TypeScript where being faithful would make it worse. Dates are the clearest case: the React Native app renders `MMM d, yyyy` through date-fns, which is English on every device. The port hands the raw stored string to the platform's own date formatting, so a phone set to Spanish shows a Spanish date. The pure layer therefore decides *which* dates to show and leaves formatting to the platform — which is also why `formatStoredDate` takes its timezone and locale as arguments rather than reading them from the system: a timestamp's date depends on the zone it is read in, and that is the part worth pinning down in a test.

`:data` reaches SQLite through a small `SqlDriver` interface rather than depending on `androidx.sqlite`. On Android that wraps a `SupportSQLiteDatabase`; in the tests it wraps JDBC. Both run the same SQL against the same schema, which is what lets the queries be exercised without an emulator.

Multi-statement writes there are transactional, which the TypeScript's are not: deleting a garment issues four statements in sequence, so a failure partway through can leave a garment gone but its learned pair scores behind.

Restoring a backup lives there too — the code that decides whether a `.zip` is allowed to replace a wardrobe, and then replaces it. Deliberately written against `java.io.File` and `java.util.zip` rather than anything from the Android SDK, because those are the same APIs Android offers: it means the one part of this app that can destroy a wardrobe runs in an ordinary JVM test, against real directories and real SQLite files. The rollback in particular is only worth trusting if it has been made to run, and on a device the way to make it run is to break a restore for real.

So the tests break it on purpose. The swap is four moves — both originals aside, then both replacements in — and each one is failed in turn, with the wardrobe checked afterwards for being byte-for-byte where it started. Refusals are compared on the *message*, not just accept-or-reject, because the message is the only thing telling someone whether to update the app, re-export the backup, or give up on the file. Only the document picker and the SQLite connection are Android's side of it.

The schema those tests run against is emitted from `src/db/schema.ts` as `schema-fresh.sql` and `schema-upgraded.sql`, so the port is tested against the schema the app really applies rather than a copy that can drift. Every read-path test runs against both, because the two are not the same shape (see Limitations).

The React Native app is untouched and keeps shipping; nothing is removed until the native app reaches parity.

Because it is a port rather than a rewrite, the tests ask whether the Kotlin **agrees with the TypeScript it came from**, not merely whether it passes tests written for it. `npm run parity:dump` records the TypeScript answers for a fixed corpus — 3096 cases across 18 files: 1156 colour pairs, 169 tag-set pairs, 60 duplicate scenarios, 700 category/subcategory pairings, 432 engine runs, 222 rating folds, 213 list, form, detail, filter and chart states, 93 row and photo-reference shapes, 41 backup archives, and the category and size lists themselves — and the Kotlin tests replay it. The engine is compared draw for draw: both sides step the same linear congruential generator, so an agreeing outfit list means every intermediate choice matched — the same template, epsilon branch, tie-break and roulette slot.

Drift is caught from both sides. CI regenerates the fixtures and fails if they moved, so changing a TypeScript algorithm without regenerating cannot leave the port pinned to old behaviour; and the Kotlin tests fail if the fixtures move without the Kotlin following. After changing either side, run `npm run parity:dump` and commit the result.

## Testing

```bash
npm run typecheck  # tsc --noEmit
npm test           # vitest, one-shot
npm run test:watch
```

27 suites, 312 tests, covering the suggestion engine, duplicate detection, colour comparison, backup validation, the database lock and migrations, URL import and which addresses it will fetch, garment and outfit services, what a garment's detail screen shows, how the outfit filters behave, the analytics bar arithmetic, the domain layer's dependency-freedom, and the pure utilities.

The Kotlin port adds 196 more:

```bash
cd native && ./gradlew test
```

`typecheck`, `test` and the Kotlin domain tests all run in CI on every pull request.

Domain algorithms are checked by mutation: each behaviour the tests claim to protect is removed in turn, and the intended test must fail. A test that passes without the code it covers is not a test.

## Limitations

- **Android only.** Web and iOS support were removed — the web build had its own storage layer that could silently lose data, and iOS was never finished.
- **The native port is early.** The Kotlin app now covers the wardrobe: adding and editing garments with photos, the list, a garment's detail, outfit suggestions you can rate and keep, the analytics, and restoring a backup. CI builds it as a debug APK. It installs under its own application id (`com.anonymous.wardrobapp.dev`) alongside the React Native app rather than replacing it, so a wardrobe gets in there by restoring a backup rather than by being found. Still missing: background removal (which needs a segmentation model the port does not have), URL import, settings, and any language but English. The shipped app is still the React Native one.
- **The `garments` schema is not uniform.** `created_at` and `updated_at` are `NOT NULL` on a fresh install but nullable on one upgraded through the `ALTER` path, because SQLite cannot add a `NOT NULL` column without a default. Both populations exist, so readers must tolerate both — and it is why the native data layer will use plain SQL rather than Room, whose schema validation would reject one of them.
- **No cloud sync**, by design. Backups are the way to move a wardrobe to another device.
- **Released APKs are debug-signed**, so they can't be upgraded in place from a properly signed build later.
- **The schema is applied idempotently** at startup from `src/db/schema.ts` — `CREATE TABLE IF NOT EXISTS`, then additive `ALTER`s, then the indexes over them. There's no `PRAGMA user_version` yet. Keyed, run-once data migrations live in `src/db/migrations.ts`.
- **No wear log.** The app records outfit ratings, not what you wore on a given day, so there is no cost-per-wear or wear-trend reporting.
- **URL import only fetches public addresses.** A `wardrobapp://…?importUrl=…` deep link can be opened by any web page, message or QR code, so the address it carries is not necessarily one you chose. Import therefore asks before fetching anything, and refuses addresses on the device or its local network — a phone sits *inside* a home network, and without that the app would be a way to reach a router or a printer that the page could not reach itself. The check is re-applied after redirects and to the image URLs the page supplies, and page reads are capped and given a deadline. One residual: a redirect to a private address is refused *after* the request has been made, since React Native's fetch cannot be told to stop at a redirect — nothing is read back from it.

## Contributing

This is a personal project, but issues and PRs are welcome. For anything non-trivial, open an issue first so we can talk through scope. Pull requests must pass `typecheck` and `test`.

By contributing, you agree that your contributions are licensed under the project's AGPL-3.0 license.

## License

[AGPL-3.0](LICENSE) — GNU Affero General Public License v3.0.

In plain English: you can use, modify, and redistribute Wardrobapp freely, including running it as a hosted service, **provided that** you publish the full source of your modified version under the same AGPL-3.0 license. For closed-source or proprietary commercial use, contact the author to discuss a commercial license.
