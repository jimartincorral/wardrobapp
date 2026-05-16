# Wardrobapp

A local-first wardrobe and outfit planner built with React Native + Expo. Catalog the clothes you own, log what you wear, and get outfit suggestions that learn from your ratings — all stored on-device.

> Status: pre-1.0, actively developed. The roadmap lives in [TODO.md](TODO.md).

## Features

- **Garment catalog** — photo, category, color palette, tags, brand, size, price. Photos are auto-resized to 800px / 70% JPEG to keep the DB small.
- **Local background removal** — strip backgrounds from garment photos in-browser via [`@imgly/background-removal`](https://github.com/imgly/background-removal-js) (WASM, runs client-side).
- **Duplicate detection** — when you add a new garment, the app warns about likely duplicates using a weighted score (0.6 × tag Jaccard + 0.3 × color CIE76 ΔE + 0.1 × size match).
- **Outfit suggestions** — epsilon-greedy engine that combines category templates, color harmony, season/weather/occasion filters, and pair scores learned from your ratings.
- **Wear log + analytics** — track what you wore and when. Cost-per-wear, monthly trends, garment lifespan stats.
- **Backup/restore** — local JSON export with embedded base64 images. Google Drive backup is scaffolded but requires a dev build (see *Limitations*).
- **Multi-platform** — runs on Android, iOS, and the web. SQLite on native; an in-memory adapter with `localStorage` persistence on web.

## Tech stack

- **Runtime:** React Native 0.83, Expo SDK 55, React 19
- **Navigation:** expo-router (file-based, typed routes)
- **Storage:** expo-sqlite (WAL mode) on native, in-memory adapter on web
- **State:** zustand
- **Images:** expo-image-picker + expo-image-manipulator
- **Background removal:** `@imgly/background-removal` (WASM, web only)
- **Language:** TypeScript

## Getting started

### Prerequisites

- Node.js 20+ and npm
- For Android: Android Studio + SDK (or use Expo Go for managed-workflow testing)
- For iOS: Xcode + a Mac (or use Expo Go)

### Install & run

```bash
git clone https://github.com/jimartincorral/wardrobapp.git
cd wardrobapp
npm install

# Web — full feature set including background removal
npm run web

# Native — scan the QR code with Expo Go on your phone
npm start

# Or build to a connected Android device / emulator
npm run android
```

The first launch initializes the SQLite schema automatically.

### Optional: pre-bundle the background-removal WASM blob

```bash
npm run bundle:bg-removal
```

This produces `public/vendor/imgly-background-removal.bundle.mjs` so the web build doesn't pull from a CDN at runtime.

## Project structure

```
app/                 Expo Router screens
  (tabs)/              index, wardrobe, outfits, analytics
  garment/             add + [id] (detail/edit)
  outfit/[id].tsx      outfit detail
  settings.tsx
src/
  db/                  SQLite client + web in-memory adapter + schema
  services/            Business logic (garment, outfit, wear, analytics,
                       duplicate-detector, suggestion-engine, image,
                       background-removal, backup, url-import)
  components/          GarmentCard, TagInput, ColorPicker, RatingStars, ...
  constants/           Categories, color palettes, theme tokens
  utils/               Color distance (CIE76 ΔE), tag similarity (Jaccard),
                       date helpers
  hooks/  i18n/  theme/  types/
assets/                App icon, splash, favicon
```

## Testing

```bash
npm test           # one-shot
npm run test:watch # watch mode
```

Tests use Vitest. The current suites cover the suggestion engine and URL import service.

## Limitations & roadmap notes

- **Google Drive backup** requires `@react-native-google-signin/google-signin` native modules and won't work in Expo Go. Use a dev build (`eas build --profile preview`). Local JSON backup works everywhere.
- **Background removal** is web-only today; a native equivalent is on the TODO.
- **No cloud sync** between devices — by design, this is a local-first app.
- **Migrations** aren't versioned yet; schema lives in raw SQL inside `src/db/client.ts`. See the *Phase 3* section of [TODO.md](TODO.md).

## Contributing

This is currently a personal project, but issues and PRs are welcome. If you're planning a non-trivial change, open an issue first so we can talk through scope.

By contributing, you agree that your contributions are licensed under the project's AGPL-3.0 license (see below).

## License

[AGPL-3.0](LICENSE) — GNU Affero General Public License v3.0.

In plain English: you can use, modify, and redistribute Wardrobapp freely, including running it as a hosted service, **provided that** you publish the full source code of your modified version under the same AGPL-3.0 license. If you want to use Wardrobapp in a closed-source or proprietary commercial product, contact the author to discuss a commercial license.
