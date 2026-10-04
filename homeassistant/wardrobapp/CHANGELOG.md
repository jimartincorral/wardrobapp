# Changelog

## 0.4.0

- Restoring a backup on a phone that syncs with this app now replaces the
  wardrobe here too, and on every other phone that syncs with it, instead of
  being undone by the next sync. Phones need this version to send a restored
  wardrobe.

## 0.3.0

- A wardrobe for each person. The first time you open the app it asks whose
  wardrobe this is: choose yours, or start a new one. After that it opens
  yours. Switch, rename or add wardrobes under **Settings → Wardrobe**.
- The wardrobe you already had is kept as it was, as **The original
  wardrobe**, and phones paired with it keep syncing with it.
- Each wardrobe has its own pairing code, so each person's phone syncs with
  their own wardrobe.
- After an update, the app shows what's new in it.

## 0.2.0

- The Android app can now sync with this one. Give the phone sync port a host
  port in the Network settings, and pair the phone with the code under
  **Settings → Phone sync**. Changes made on the phone and in the browser reach
  each other; when both changed the same thing, the latest change wins.
- **Make a new code** unpairs every phone at once.

## 0.1.0

- The first version: the whole wardrobe in Home Assistant's sidebar, with
  outfit suggestions, statistics and URL import, kept in Home Assistant's own
  storage and backups.
