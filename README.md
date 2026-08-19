# Wardrobapp

A local-first wardrobe and outfit planner for Android, built with React Native + Expo. Catalog the clothes you own, log what you wear, and get outfit suggestions that learn from your ratings — all stored on-device.

> Status: pre-1.0, actively developed. The roadmap lives in [TODO.md](TODO.md).

## Features

- **Garment catalog** — photo, category, color palette, tags, brand, size, price. Photos are auto-resized to 800px / 70% JPEG to keep the DB small.
- **On-device background removal** — strip backgrounds from garment photos via [`@six33/react-native-bg-removal`](https://www.npmjs.com/package/@six33/react-native-bg-removal). Runs locally; needs a dev or release build, not Expo Go.
- **Duplicate detection** — when you add a new garment, the app warns about likely duplicates using a weighted score (0.6 × tag Jaccard + 0.3 × color CIE76 ΔE + 0.1 × size match).
- **Outfit suggestions** — epsilon-greedy engine that combines category templates, color harmony, season/weather/occasion filters, and pair scores learned from your ratings.
- **Wear log + analytics** — track what you wore and when. Cost-per-wear, monthly trends, garment lifespan stats.
- **Backup/restore** — a single `.zip` holding the SQLite database and your garment photos, written to a folder you pick. Staged and zipped on disk, so memory stays flat no matter how large the wardrobe is.

## Tech stack

- **Runtime:** React Native 0.83, Expo SDK 55, React 19
- **Navigation:** expo-router (file-based, typed routes)
- **Storage:** expo-sqlite (WAL mode)
- **State:** React Context
- **Images:** expo-image-picker + expo-image-manipulator
- **Background removal:** `@six33/react-native-bg-removal` (on-device)
- **Language:** TypeScript

## Getting started

### Prerequisites

- Node.js 20+ and npm
- Android Studio + SDK (or use Expo Go for managed-workflow testing)

### Install & run

```bash
git clone https://github.com/jimartincorral/wardrobapp.git
cd wardrobapp
npm install

# Scan the QR code with Expo Go on your phone
npm start

# Or build to a connected Android device / emulator
npm run android
```

Background removal and backup/restore need the native modules, so they only
work in a dev or release build — `npm run android`, not Expo Go.

The first launch initializes the SQLite schema automatically.

### Build a release APK (Android)

```bash
npm run apk
```

This runs a Gradle `assembleRelease` build via `scripts/build-apk.ps1` and prints the
output path (`android/app/build/outputs/apk/release/app-release.apk`). The script
auto-detects a valid JDK 17 at build time, so it isn't affected by a stale `JAVA_HOME`.
Requires the native project to exist — run `npx expo prebuild` once if `android/` is missing.

## Project structure

```
app/                 Expo Router screens
  (tabs)/              index, wardrobe, outfits, analytics
  garment/             add + [id] (detail/edit)
  outfit/[id].tsx      outfit detail
  settings.tsx
src/
  db/                  SQLite client + schema + data migrations
  services/            Business logic (garment, outfit, wear, analytics,
                       duplicate-detector, suggestion-engine, image,
                       background-removal, backup, url-import)
  components/          GarmentCard, TagInput, ColorPicker, RatingStars, ...
  constants/           Categories, color palettes, theme tokens
  utils/               Color distance (CIE76 ΔE), tag similarity (Jaccard),
                       date helpers
  hooks/  i18n/  theme/  types/
assets/                App icon, splash, adaptive icon layers
```

## Testing

```bash
npm test           # one-shot
npm run test:watch # watch mode
```

Tests use Vitest — 12 suites covering the suggestion engine, URL import, backup archive parsing, garment and outfit services, data migrations, and the color/tag/date utilities.

## Limitations & roadmap notes

- **Android only.** iOS and web were dropped — see `platforms` in `app.json`.
- **Background removal and backup/restore need native modules**, so they don't work in Expo Go. Use `npm run android` for development or `npm run apk` for a release APK.
- **No cloud sync** between devices — by design, this is a local-first app.
- **Migrations** aren't versioned yet; schema lives in raw SQL inside `src/db/client.ts`. See the *Phase 3* section of [TODO.md](TODO.md).

## Contributing

This is currently a personal project, but issues and PRs are welcome. If you're planning a non-trivial change, open an issue first so we can talk through scope.

By contributing, you agree that your contributions are licensed under the project's AGPL-3.0 license (see below).

## License

[AGPL-3.0](LICENSE) — GNU Affero General Public License v3.0.

In plain English: you can use, modify, and redistribute Wardrobapp freely, including running it as a hosted service, **provided that** you publish the full source code of your modified version under the same AGPL-3.0 license. If you want to use Wardrobapp in a closed-source or proprietary commercial product, contact the author to discuss a commercial license.
