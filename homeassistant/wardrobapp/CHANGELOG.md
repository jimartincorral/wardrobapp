# Changelog

## 0.8.0

- Settings shows a QR code for pairing a phone, and says when the sync port
  still has to be opened. The app asks Home Assistant for access to the
  Supervisor API to find out which host port you gave it; that is all it does
  with it.
- A backup restored on a phone away from home no longer deletes, once the
  phone is back, what was added here in the meantime. What the backup lacks
  now counts as deleted from the moment of the restore, so anything added
  after it stays.
- A photo too large to cut out safely is refused with its size, instead of
  the server running out of memory.

## 0.7.0

- A layout for wide screens. On a computer, the app uses the whole window:
  a rail down the side instead of the bar along the bottom, and the wardrobe
  shows its filters, your clothes and the garment you pick side by side. Home,
  Outfits, Statistics, Settings and adding clothes are laid out for the room
  too. On a phone, or a narrow window, nothing changes.

## 0.6.0

- Remove a photo's background in the browser, from the garment form, from
  **Add several photos** and from a saved garment, as on the phone. The app
  does the work, so it takes a few seconds on a Raspberry Pi.

## 0.5.0

- A wardrobe can be deleted, under **Settings → Wardrobe → Delete**. Its
  clothes, outfits and photos are deleted from Home Assistant. A phone that
  synced with it keeps its own copy.

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
