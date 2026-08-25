# Wardrobapp

A local-first wardrobe and outfit planner for **Android**, written in Kotlin and Compose. Catalog the clothes you own, rate outfit suggestions, and let the app learn what you like — all stored on-device, with no account and no server.

> Status: pre-1.0, actively developed. The roadmap lives in [TODO.md](TODO.md).

## Features

- **Garment catalog** — photos, category and type, colour palette, tags, brand, size. The colours fill themselves in from the photo as it is added, and again if you remove its background. A photo is cropped to 3:4 as it is added, from the gallery or the camera, which is the shape every screen shows a garment in; it is then resized to 800px and re-encoded at 70% JPEG to keep the database and backups small.
- **The numbers are links** — the counts on the home screen open the wardrobe, and each bar in the statistics category chart has a small button that opens the wardrobe filtered to it. A link clears whatever was filtered before, so the list always has as many garments in it as the number that was tapped.
- **A wardrobe you can look at your way** — the list, or a grid two, three or four garments across, chosen from the top bar and remembered between launches. A cell is the photo with the garment's brand under it, since that is the one thing a photo does not show.
- **On-device background removal** — strips the background from a garment photo using ML Kit subject segmentation, from the add/edit form or from a garment already saved. The cut-out replaces the original rather than sitting beside it, so removing a background costs storage instead of doubling it.
- **Duplicate detection** — when you add a garment, likely duplicates in the same category are flagged by a weighted average of tag overlap (Jaccard, 0.6), colour similarity (0.3) and size match (0.1). Signals with nothing to compare abstain rather than scoring zero, so an untagged garment can still be recognised as a duplicate.
- **Outfit suggestions** — an epsilon-greedy engine combining category templates, colour harmony judged by hue, season and occasion fit, and pair scores learned from your ratings.
- **Import from a link** — paste or share a product page and the garment is filled in from it: photos, title, brand. Only public addresses are fetched, and the app asks before going anywhere a link it did not choose points at.
- **Filters that know your wardrobe** — every filter offers the values you actually own: your brands, your sizes, the colours your garments come in. Each row is one line you scroll sideways, and the choices narrow as you pick, so a combination that would show nothing is never offered.
- **Statistics** — one page: six counts at a glance, then breakdowns by category (with subcategories), colour and brand, and how long the garments you retire lasted. Each breakdown is a section you open, so the page starts with the numbers rather than six charts.
- **Storage that tidies itself** — Optimize storage in Settings shrinks cut-outs an older build wrote at full resolution and deletes photos no garment points at any more. Anything written in the last hour is left alone, so a garment you are still filling in is never touched.
- **Backup and restore** — a single `.zip` containing the SQLite database and every photo, written to a folder you pick. Restore stages and verifies the archive before replacing anything, and rolls back if it can't finish.
- **Updates itself** — the app is not on an app store, so at every launch it reads a small document published beside the APK on the rolling release and says so when a newer build exists, with the changelog since the build on the phone. Install, skip that build, or later. The download address is fixed and every redirect is checked against this project's own release hosts before it is followed.
- **English and Spanish** — full UI localization, following the per-app language setting or overridden in Settings.

## Tech stack

- **UI:** Jetpack Compose, Material 3, single activity, Navigation Compose
- **Language:** Kotlin 2.1, JVM target 17, `minSdk` 24 / `targetSdk` 36
- **Storage:** SQLite through a small `SqlDriver` interface — `SupportSQLiteDatabase` on a device, JDBC in tests; photos as files under `<documents>/garment-images/`, referenced from the database by filename
- **Build:** Gradle with AGP 8.9, four modules, no code generation
- **Dependencies:** AndroidX, ML Kit, Coil for loading photos, and the same crop screen Expo's image picker used. No dependency injection framework, no ORM.

## Getting started

### Prerequisites

- JDK 17
- The Android SDK, with platform 36 (Android Studio, or `sdkmanager`)

Three of the four modules need neither — see [Architecture](#architecture).

```bash
git clone https://github.com/jimartincorral/wardrobapp.git
cd wardrobapp

# The pure modules: the algorithms, the data mapping, the view logic.
# No Android SDK needed, and finishes in seconds.
./gradlew test

# The app, on a connected device or emulator.
./gradlew installDebug
```

A debug build installs as `com.anonymous.wardrobapp.debug`, alongside the real app rather than over it. The first launch creates the SQLite schema.

### Build a release APK

```bash
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

CI builds one on every push and publishes it from `main` to the rolling [`nightly` release](https://github.com/jimartincorral/wardrobapp/releases/tag/nightly), which installs over whatever version is on the phone and keeps the wardrobe.

`versionCode` is a fixed offset plus the CI run number, so each published build is a later version than the last. Locally it is the offset alone, which is fine: a local build is not upgrading anything.

### Signing

Every published build so far — including the ones from before this was a Kotlin app — has been signed with the **public Android debug key** that ships inside Expo's project template. `app/debug.keystore` is that key, committed on purpose: Android replaces an installed app only with a build signed by the same key, so this is what makes a release an upgrade rather than a second app. `app/build.gradle.kts` says so at length beside the config, and CI verifies every release APK carries it.

It is a public key. Anybody can sign an APK with it, so a build claiming to be an update of this app cannot be told apart from one — trust the source rather than the signature. The fix is a keystore only its owner holds:

**1. Create one.** Needs a desktop; it cannot be done from the phone. Keep the file and both passwords somewhere you will still have them in five years — losing them means never being able to upgrade an installed app again.

```bash
keytool -genkeypair -v -keystore wardrobapp-release.keystore \
  -alias wardrobapp -keyalg RSA -keysize 2048 -validity 10000
```

**2. Add four repository secrets** (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | the keystore, base64-encoded (`base64 -w0 wardrobapp-release.keystore`) |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | the key alias (`wardrobapp` above) |
| `ANDROID_KEY_PASSWORD` | the key password |

CI decodes the keystore, passes the rest to Gradle as `ORG_GRADLE_PROJECT_WARDROBAPP_*` properties, and inverts its signing check: with a keystore configured, an APK still carrying the public key fails the build. The same four properties work locally (`-PWARDROBAPP_STORE_FILE=…`).

**3. Reinstall once, on each device.** Android refuses an APK whose signature differs from the installed app's, so the first properly-signed build is a clean break: **back up from Settings, uninstall, install the signed APK, restore the backup.** Every later build upgrades in place again.

## Project structure

```
app/           The Android app: Compose screens, ViewModels, and the platform
               plumbing that genuinely needs Android — the camera, the document
               picker, SQLite, ML Kit, the share target. 30 files, and the only
               module that needs the SDK.
presentation/  What a screen shows, as pure functions over records: list
               filtering and ordering, form state, the detail view, the chart
               arithmetic, the colour a photo suggests.
domain/        The algorithms: outfit suggestion, duplicate detection, pair
               learning, colour comparison, occasions, URL safety, reading a
               product page.
data/          SQLite queries and row mapping, photo references, reading and
               writing backup archives.
scripts/       generate-launcher-icons.py — draws the launcher icons, no
               dependencies.
```

## Architecture

One Android module and three plain Kotlin/JVM ones. `settings.gradle.kts` includes `:app` only when an Android SDK is present, which is what lets the other three be built and tested on any machine — and proves they need nothing but a JDK, rather than merely claiming it.

- **`domain/`** — no database, no filesystem, no clock, no Android. Everything arrives as an argument: the suggestion engine takes its randomness as a parameter, so a run is reproducible and a bug can be reported.
- **`presentation/`** — the decisions a screen makes, taken out of the screen. Chart widths, what counts as an active filter, which photo the strip has selected. Compose renders the answers; it does not compute them.
- **`data/`** — reaches SQLite through a small `SqlDriver` interface rather than depending on `androidx.sqlite`. On Android that wraps a `SupportSQLiteDatabase`; in tests it wraps JDBC. Both run the same SQL against the same schema, which is what lets the queries be exercised without an emulator.
- **`app/`** — layout, navigation, and the platform. Thin on purpose: a ViewModel here loads data, calls a pure function and holds the result.

`WardrobeSchema` is applied on every open — `CREATE TABLE IF NOT EXISTS`, then additive `ALTER`s, then the indexes over them — so there is no migration version to get out of step. Two shapes of database exist on real phones as a result, and both are tested; see Limitations.

### Before this was a Kotlin app

Until [`0ca397a`](https://github.com/jimartincorral/wardrobapp/commit/0ca397a) this repository held a React Native app, and this one is a port of it rather than a rewrite: the same schema, the same photo layout, the same archive format, the same algorithms down to the arithmetic. That app is deleted, and its last version is one commit away if it is ever needed.

Two things it left behind, both deliberate. Comments across this codebase explain a decision by referring to "the React Native app" — that is the app above, and those explanations are still why the code looks as it does: the database lives in `files/SQLite/` because that app put it there, and it is still there on every phone that has ever run this one. And the port was verified against it by recording 3341 of its answers and replaying them in Kotlin; that corpus went when its oracle did, replaced by tests that state what has to be true rather than that two implementations agree.

## Testing

```bash
./gradlew test                    # 519 tests, no Android SDK, seconds
./gradlew :app:testDebugUnitTest  # 80 more, needs the SDK — no emulator
```

The 519 cover the suggestion engine, duplicate detection, colour comparison, pair learning, URL safety and which addresses will be fetched, reading a product page, row normalization against every list-column shape that exists, the two database schemas in the wild, backup validation and its refusal messages, which published build is worth offering and where an update may be downloaded from, the form rules, filtering and ordering, the chart arithmetic, and both languages' string resources against each other.

The 80 in `:app` are Robolectric tests, not instrumented ones — what a screen shows, where a file lands, and what another activity is asked for, which is the part no pure module can answer:

```bash
./gradlew :app:testDebugUnitTest
```

The debug variant by name, not `:app:test`: `ui-test-manifest` supplies the activity the Compose tests compose into, and it is a debug-only artifact by design, so running them against release fails every one of them.

Lint runs every check it has, with warnings failing the build and no baseline file — the backlog was cleared rather than frozen. The two version-nag checks are informational, since a new AndroidX release is not a defect in any commit.

Algorithms are checked by mutation: each behaviour the tests claim to protect is removed in turn, and the intended test must fail. A test that passes without the code it covers is not a test.

CI runs all of it on every pull request, on pushes to `main`, and on pushes to `claude/**` branches — the Android job is the only place `:app` compiles or lints at all, so a branch needs to be able to run it without opening a pull request first.

## Limitations

- **Android only.** iOS was never finished and web was removed; the storage layer it used could silently lose data.
- **Old backups cannot be listed or deleted from inside the app.** Deliberately: it would mean holding a persistent directory grant the app does without — archives go in and out through the document picker with no storage permission at all, and the Files app already deletes a zip.
- **`garments` is not one schema.** `created_at` and `updated_at` are `NOT NULL` on a fresh install and nullable on one upgraded through the `ALTER` path, because SQLite cannot add a `NOT NULL` column without a default. Both populations exist on phones, so readers tolerate both — and it is why this layer uses plain SQL rather than Room, whose schema validation would reject one of them.
- **Releases are signed with a public key** until a keystore exists. See [Signing](#signing).
- **Updating means installing an APK.** There is no store to go through, so the app asks Android to install the build it downloaded, which needs `REQUEST_INSTALL_PACKAGES` and a one-time "allow from this source" grant. The permission is the ability to *offer* an install: the system asks, names this app as the source, and declining leaves the phone as it was. The check itself is one request per launch to a fixed address, and it is silent when it fails — no network, a captive portal, GitHub down.
- **No cloud sync**, by design. Backups are the way to move a wardrobe to another device.
- **No wear log.** The app records outfit ratings, not what you wore on a given day, so there is no cost-per-wear or wear-trend reporting.
- **Colour detection is approximate.** It snaps every fourth pixel to the nearest of the 24 palette colours and picks whichever holds the most of them, plus a runner-up covering at least a fifth of the garment. What is left approximate: a print reports its ground and its strongest figure rather than "multi", the third colour of a three-coloured garment is not reported, and on a photo whose background has not been removed that background still votes. It fills the palette in rather than answering it — every colour it picks is one tap to undo.
- **URL import only fetches public addresses.** A product page reaches the app two ways: a `wardrobapp://…?importUrl=…` deep link, which any web page, message or QR code can open, and the share sheet from a browser. Neither is necessarily an address you chose. Import therefore asks before fetching anything, and refuses addresses on the device or its local network — a phone sits *inside* a home network, and without that the app would be a way to reach a router or a printer that the page could not reach itself. Redirects are checked before they are followed, page reads are capped and given a deadline, and the image URLs a page supplies go through the same check. An `http://` page will not load at all: Android blocks cleartext by default and opting in app-wide to reach the occasional shop still on http would weaken every other request.

## Contributing

This is a personal project, but issues and PRs are welcome. For anything non-trivial, open an issue first so we can talk through scope. Pull requests must pass `./gradlew test` and the Android job.

By contributing, you agree that your contributions are licensed under the project's AGPL-3.0 license.

## License

[AGPL-3.0](LICENSE) — GNU Affero General Public License v3.0.

In plain English: you can use, modify, and redistribute Wardrobapp freely, including running it as a hosted service, **provided that** you publish the full source of your modified version under the same AGPL-3.0 license. For closed-source or proprietary commercial use, contact the author to discuss a commercial license.
