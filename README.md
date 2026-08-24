# Wardrobapp

A local-first wardrobe and outfit planner for **Android**, built with React Native + Expo. Catalog the clothes you own, rate outfit suggestions, and let the app learn what you like — all stored on-device, with no account and no server.

> Status: pre-1.0, actively developed. The roadmap lives in [TODO.md](TODO.md).

## Features

- **Garment catalog** — photos, category and type, colour palette, tags, brand, size. Photos are resized to 800px and re-encoded at 70% JPEG on import to keep the database and backups small.
- **On-device background removal** — strips the background from a garment photo via ML Kit subject segmentation: [`@six33/react-native-bg-removal`](https://www.npmjs.com/package/@six33/react-native-bg-removal) in the React Native app, the same model called directly in the Kotlin port. Needs the native module linked, so a development or release build rather than Expo Go.
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

`versionCode` comes from `app.config.js`: a fixed offset plus the CI run number, so each published build is a later version than the last. Locally it is the offset alone, which is fine — a local build is not upgrading anything.

### Signing releases

Not set up yet: with no keystore configured, both apps fall back to the public Android debug key, and everything below is what remains to finish. All the plumbing is in place, so this is four secrets and a key.

**1. Create a keystore.** This needs a desktop machine; it cannot be done from the phone. In Android Studio: **Build → Generate Signed App Bundle / APK → APK → Create new…**, and keep the file and both passwords somewhere you will still have them in five years — losing them means never being able to upgrade an installed app again. The `keytool` equivalent, if you would rather not open Android Studio:

```bash
keytool -genkeypair -v -keystore wardrobapp-release.keystore \
  -alias wardrobapp -keyalg RSA -keysize 2048 -validity 10000
```

**2. Add four repository secrets** (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | the keystore, base64-encoded (see below) |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | the key alias (`wardrobapp` above) |
| `ANDROID_KEY_PASSWORD` | the key password |

Encode the keystore with `base64 -w0 wardrobapp-release.keystore` on Linux, `base64 -i wardrobapp-release.keystore` on macOS, or in PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('wardrobapp-release.keystore'))
```

CI decodes the keystore, passes the rest to Gradle as `ORG_GRADLE_PROJECT_WARDROBAPP_*` properties, and then fails the build if the APK still carries the debug certificate — a misconfigured signing config otherwise produces a "release" APK that looks fine right up until a phone refuses to upgrade it. While `ANDROID_KEYSTORE_BASE64` is unset the build behaves exactly as it does now and says so in the release notes, so a fork or a contributor without the secret still gets a working APK. `plugins/withReleaseSigning.js` makes the same conditional edit to the generated `android/app/build.gradle`; `native/app/build.gradle.kts` reads the same four properties, so both apps end up signed by the same key.

**3. Reinstall once, on each device.** A debug-signed app cannot be upgraded by a release-signed one — Android refuses an APK whose signature differs from the installed app's. So the first signed build is a clean break: **back up from Settings, uninstall, install the signed APK, restore the backup.** Once past that, every later build upgrades in place.


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
                          a photo's dominant colour, image paths, garment
                          fields, dates, style tags, which URLs are safe to
                          fetch
  constants/  i18n/  theme/  types/
assets/                   App icon and splash
scripts/                  build-apk.ps1, dump-domain-parity.ts,
                          generate-launcher-icons.py
native/                   The Kotlin/Android port (see Architecture)
  domain/                   Ported algorithms, plain Kotlin/JVM — no Android SDK
                            needed to build or test
  data/                     Row and photo-reference mapping into domain types,
                            reading and writing backup archives
  presentation/             List filtering and ordering, form state, the colour
                            a photo suggests — pure
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
| `:domain` | The algorithms — colour, tags, occasions, duplicates, suggestions — the garment vocabulary they key on, and which addresses a link may reach plus what a product page says |
| `:data` | The database — row and photo-reference mapping, reads, writes, analytics, suggestion loading, duplicate candidates, photo storage rules, backup restore, which stored photos a tidy-up would touch |
| `:parity-testing` | Shared fixture-loading for the parity suites |
| `:presentation` | What the screens show — filtering, ordering, form state, a garment's detail, the outfit filters, the analytics bars, the colour a photo suggests, as pure functions |
| `:app` | The Compose UI and the platform plumbing — **only included when an Android SDK is present** |

`:app` is the one module that genuinely needs the Android SDK, so `settings.gradle.kts` includes it only when one is found (`ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`). `./gradlew test` therefore works on a machine with nothing but a JDK — which is the whole reason the other modules are pure — while CI builds everything. Keeping the pure parts pure is what lets everything so far be verified on any machine — and `:data` is the code that decides whether an *existing* wardrobe opens correctly, so it is the code most worth being able to test anywhere.

The port also differs from the TypeScript where being faithful would make it worse. Dates are the clearest case: the React Native app renders `MMM d, yyyy` through date-fns, which is English on every device. The port hands the raw stored string to the platform's own date formatting, so a phone set to Spanish shows a Spanish date. The pure layer therefore decides *which* dates to show and leaves formatting to the platform — which is also why `formatStoredDate` takes its timezone and locale as arguments rather than reading them from the system: a timestamp's date depends on the zone it is read in, and that is the part worth pinning down in a test.

`:data` reaches SQLite through a small `SqlDriver` interface rather than depending on `androidx.sqlite`. On Android that wraps a `SupportSQLiteDatabase`; in the tests it wraps JDBC. Both run the same SQL against the same schema, which is what lets the queries be exercised without an emulator.

Multi-statement writes there are transactional, which the TypeScript's are not: deleting a garment issues four statements in sequence, so a failure partway through can leave a garment gone but its learned pair scores behind.

Restoring a backup lives there too — the code that decides whether a `.zip` is allowed to replace a wardrobe, and then replaces it. Deliberately written against `java.io.File` and `java.util.zip` rather than anything from the Android SDK, because those are the same APIs Android offers: it means the one part of this app that can destroy a wardrobe runs in an ordinary JVM test, against real directories and real SQLite files. The rollback in particular is only worth trusting if it has been made to run, and on a device the way to make it run is to break a restore for real.

So the tests break it on purpose. The swap is four moves — both originals aside, then both replacements in — and each one is failed in turn, with the wardrobe checked afterwards for being byte-for-byte where it started. Refusals are compared on the *message*, not just accept-or-reject, because the message is the only thing telling someone whether to update the app, re-export the backup, or give up on the file. Only the document picker and the SQLite connection are Android's side of it.

The schema those tests run against is emitted from `src/db/schema.ts` as `schema-fresh.sql` and `schema-upgraded.sql`, so the port is tested against the schema the app really applies rather than a copy that can drift. Every read-path test runs against both, because the two are not the same shape (see Limitations).

The React Native app is untouched and keeps shipping; nothing is removed until the native app reaches parity.

Because it is a port rather than a rewrite, the tests ask whether the Kotlin **agrees with the TypeScript it came from**, not merely whether it passes tests written for it. `npm run parity:dump` records the TypeScript answers for a fixed corpus — 3341 cases across 23 files: 1156 colour pairs, 169 tag-set pairs, 60 duplicate scenarios, 700 category/subcategory pairings, 432 engine runs, 222 rating folds, 249 list, form, detail, filter and chart states, 180 addresses the importer will or will not fetch, 93 row and photo-reference shapes, 41 backup archives, 15 photos read for their dominant colour, 14 product pages, and the category and size lists themselves — and the Kotlin tests replay it. The engine is compared draw for draw: both sides step the same linear congruential generator, so an agreeing outfit list means every intermediate choice matched — the same template, epsilon branch, tie-break and roulette slot.

Drift is caught from both sides. CI regenerates the fixtures and fails if they moved, so changing a TypeScript algorithm without regenerating cannot leave the port pinned to old behaviour; and the Kotlin tests fail if the fixtures move without the Kotlin following. After changing either side, run `npm run parity:dump` and commit the result.

## Testing

```bash
npm run typecheck  # tsc --noEmit
npm test           # vitest, one-shot
npm run test:watch
```

31 suites, 379 tests, covering the suggestion engine, duplicate detection, colour comparison, backup validation, the database lock and migrations, URL import and which addresses it will fetch, garment and outfit services, what a garment's detail screen shows, how the outfit filters behave, the analytics bar arithmetic, the domain layer's dependency-freedom, and the pure utilities.

The Kotlin port adds 403 more. 367 of them need nothing but a JDK, which is the
point of the layering:

```bash
cd native && ./gradlew test
```

The other 36 are in `:app` and need the Android SDK, so they run in CI rather than
everywhere. They are Robolectric tests, not instrumented ones — what a screen shows
and where a file lands, which is the part of this app no pure module can answer —
so CI needs an SDK but no emulator:

```bash
cd native && ./gradlew :app:testDebugUnitTest
```

The debug variant by name, not `:app:test`: `ui-test-manifest` supplies the
activity the Compose tests compose into, and it is a debug-only artifact by
design, so running them against release fails every one of them.

`typecheck`, `test`, the Kotlin tests and the Android build all run in CI on every
pull request.

Domain algorithms are checked by mutation: each behaviour the tests claim to protect is removed in turn, and the intended test must fail. A test that passes without the code it covers is not a test.

## Limitations

- **Android only.** Web and iOS support were removed — the web build had its own storage layer that could silently lose data, and iOS was never finished.
- **The native port is close, and not finished.** The Kotlin app now covers what the shipped one does: adding and editing garments by photo, camera or product link, on-device background removal from both the form and a garment's detail, colour detection, the wardrobe list with its filters, outfit suggestions you can rate and keep, the analytics and statistics, and a settings screen that writes backups, restores them, shrinks photos an older build left large, and switches language and theme. Every string is translated. CI builds it as a debug APK and publishes it to a rolling [`port-preview`](https://github.com/jimartincorral/wardrobapp/releases/tag/port-preview) prerelease, so it can be installed and tried on a phone — debug-signed, alongside the shipped app, replaced by each new build of the port.

  It installs under its own application id (`com.anonymous.wardrobapp.dev`) alongside the React Native app rather than replacing it, so a wardrobe gets in there by restoring a backup — and back out the same way, since both apps read and write the same archive format.

  What is genuinely still missing is small and mostly not features: listing and deleting old backups from inside the app (which would mean holding a persistent directory grant the port currently does without — backups go in and out through the document picker with no storage permission at all, and the Files app already deletes a zip); a full `lint` pass, which needs a baseline recorded first so the existing backlog is frozen rather than failing the build; and the cutover itself. The shipped app is still the React Native one.

- **Two places the port deliberately behaves differently.** A redirect is checked *before* it is followed, which closes the residual risk described under URL import below — `HttpURLConnection` can be told not to follow one, and React Native's fetch cannot. And an `http://` page will not load at all: Android blocks cleartext by default and the port does not opt in, since turning it on app-wide to reach the occasional shop still on http would weaken every other request it makes.
- **The `garments` schema is not uniform.** `created_at` and `updated_at` are `NOT NULL` on a fresh install but nullable on one upgraded through the `ALTER` path, because SQLite cannot add a `NOT NULL` column without a default. Both populations exist, so readers must tolerate both — and it is why the native data layer will use plain SQL rather than Room, whose schema validation would reject one of them.
- **No cloud sync**, by design. Backups are the way to move a wardrobe to another device.
- **Released APKs are debug-signed.** Signing is wired up but has no key yet, so the first signed build will need a one-time back-up, uninstall and restore — see [Signing releases](#signing-releases).
- **The schema is applied idempotently** at startup from `src/db/schema.ts` — `CREATE TABLE IF NOT EXISTS`, then additive `ALTER`s, then the indexes over them. There's no `PRAGMA user_version` yet. Keyed, run-once data migrations live in `src/db/migrations.ts`.
- **No wear log.** The app records outfit ratings, not what you wore on a given day, so there is no cost-per-wear or wear-trend reporting.
- **URL import only fetches public addresses.** A product page reaches the app two ways: a `wardrobapp://…?importUrl=…` deep link, which any web page, message or QR code can open, and the share sheet from a browser. Neither is necessarily an address you chose. Import therefore asks before fetching anything, and refuses addresses on the device or its local network — a phone sits *inside* a home network, and without that the app would be a way to reach a router or a printer that the page could not reach itself. The check is re-applied after redirects and to the image URLs the page supplies, and page reads are capped and given a deadline. One residual, in the React Native app only: a redirect to a private address is refused *after* the request has been made, since its fetch cannot be told to stop at a redirect — nothing is read back from it. The Kotlin port does not have it, because `HttpURLConnection` can be told not to follow, so every hop goes through the same check before the request is made.

## Contributing

This is a personal project, but issues and PRs are welcome. For anything non-trivial, open an issue first so we can talk through scope. Pull requests must pass `typecheck` and `test`.

By contributing, you agree that your contributions are licensed under the project's AGPL-3.0 license.

## License

[AGPL-3.0](LICENSE) — GNU Affero General Public License v3.0.

In plain English: you can use, modify, and redistribute Wardrobapp freely, including running it as a hosted service, **provided that** you publish the full source of your modified version under the same AGPL-3.0 license. For closed-source or proprietary commercial use, contact the author to discuss a commercial license.
