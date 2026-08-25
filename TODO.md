# Wardrobapp Product To-Do

One app now: the Kotlin one. Anything below is built once.

## Done since this list was written

- Advanced wardrobe filters — `season`, `occasion`, `size`, `brand`, and sorting.
- Favourite/pin outfits — the `is_pinned` column, `ORDER BY is_pinned DESC`, and
  pin/unpin in the UI.
- Empty states for the wardrobe, outfits and statistics screens.
- Garment lifespan, surfaced on the statistics page.
- One statistics page: the Analytics tab and the Statistics screen were the same
  question asked twice, and are now one tab with its breakdowns as sections.
- Recommendation v2: a cap on how many colours an outfit shouts in, a search that
  widens with the wardrobe, no repeating the batch just shown, and every
  suggestion saying why it came up.
- Outfit suggestions that hang together: the engine scores whether the garments
  agree about the occasion, weights its templates towards complete outfits, and
  can be pointed at one garment to build around. Rating no longer forces a save --
  rated-only outfits are archived, so the learning is kept without the list
  filling up.
- Bulk add: several photos in, a queue that asks each one for a category, and a
  garment written per confirmation.
- Getting from a number to the garments behind it: the home counts and the
  statistics categories open the wardrobe, filtered to what was tapped.
- Telling the phone that a newer build exists, with its changelog, and installing
  it — the part an app outside a store has to do for itself.
- Reclaiming photos nothing points at, folded into Optimize storage.
- Versioned backups with validation and migration safety: the format carries a
  version, a restore refuses an archive it cannot read and says why, and it stages,
  verifies and rolls back rather than overwriting in place.

## Not being built

- **A wear log**, and everything that reduced to it: monthly wear trend,
  cost-per-wear, sorting the wardrobe by most or least worn, rotation reminders,
  unused-item prompts. Recording what was worn on a given day is a feature people
  do not use, so the four features on top of it are not worth the one underneath.
  What this app learns, it learns from ratings.

  Two consequences worth stating, so they are not rediscovered as bugs: `wear_count`
  on `outfit_pair_scores` counts *ratings*, and a learned pair therefore cannot be
  weighted by how often it was actually worn; and "not worn recently" is not a
  constraint the suggestion engine can ever apply.

## Blocked on a field that does not exist

- [ ] **Price.** No garment carries one. Wanted for sorting and for what a wardrobe
      is worth. Cheap to add to the schema; the question is whether it is asked for
      on the add form or left optional.

## Ready to build

- [ ] **An outfit card** — the garment photos composed into one image. Wanted for
      its own sake (an outfit that can be looked at, and shared) and as the thing
      the manual builder and the outfit editor preview. A full AI render of the
      clothes on a body is what this would ideally be; no on-device model can do
      that yet, so this is a composition: the garments laid out by the slot they
      fill, drawn to a bitmap.
- [ ] Manual outfit builder, on top of that card:
  - [ ] Select garments by category
  - [ ] Preview the combination
  - [ ] Save the custom outfit
- [ ] Outfit editing on the outfit detail screen:
  - [ ] Rename outfit
  - [ ] Replace/remove garments
  - [ ] Update occasion/season metadata
- [ ] Outfit planning calendar:
  - [ ] Plan an outfit by date
  - [ ] View upcoming planned outfits
- [ ] Restore preview: show what an archive contains before replacing anything.
      The validation it would read from already exists.
- [ ] Recommendation engine v2, what is left of it:
  - [ ] Better personalization signals — ratings are cheap to give now that rating
        no longer forces a save, so this is a question of what else to learn from
        rather than of how to collect it
  - [ ] Context-aware constraints, minus the "recent wear" half
- [ ] Weather, if it is wanted: there is no weather concept anywhere, not a field
      and not a constant, and `season` may be enough. A temperature band per
      garment plus one tap for "cold today" would keep this local-first; a forecast
      service would mean network, location and a key, which is a different app.

## Parked

- [ ] **Cloud backup sync**, Google Drive first. `drive.file` scope rather than
      `appDataFolder`, so a backup stays something its owner can see and download.
      Blocked on a real keystore: an OAuth client is registered against the app's
      signing certificate, so this cannot be set up against a public debug key.
      See Signing in the README.
- [ ] **Scheduled backups**, which belong with the above rather than before it: a
      weekly job writing a backup and keeping the last few. Worth having on-device
      too, but a schedule whose only destination is a folder on the same phone is
      half a safety net.

## Suggested build order

1. The outfit card, then the manual builder and the outfit editor on top of it.
   Three features, one piece of new machinery.
2. Restore preview, which needs nothing new.
3. Recommendation v2's remaining half.
4. Cloud sync and the schedule with it, once there is a keystore.

The cutover to Kotlin is done, so each of these is built once. What is left of it
is a keystore: see Signing in the README.
