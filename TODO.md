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
- Bulk add: several photos in, a queue that asks each one for a category, cropping
  and background removal offered per garment, and a garment written per
  confirmation.
- Learning more than pairs from a rating: each garment's own record, and which
  kinds of colour pairing this wardrobe's owner actually likes, both blended
  towards the built-in defaults in proportion to the evidence behind them. The
  personalization half of Recommendation v2.
- Outfits built by hand and outfits changed afterwards: one screen for both, with
  the wardrobe to pick from grouped by category and searchable, a name that falls
  back to what is in the outfit, and what the outfit is for.
- Getting from a number to the garments behind it: the home counts and the
  statistics categories open the wardrobe, filtered to what was tapped.
- Telling the phone that a newer build exists, with its changelog, and installing
  it — the part an app outside a store has to do for itself.
- Reclaiming photos nothing points at, folded into Optimize storage.
- Versioned backups with validation and migration safety: the format carries a
  version, a restore refuses an archive it cannot read and says why, and it stages,
  verifies and rolls back rather than overwriting in place.

## Not being built

- **The outfit card** — the garment photos composed into one image. Built, tried
  and removed: a flat-lay laid out by the slot each garment fills reads as a grid
  of photographs rather than as an outfit, and no rearranging of rectangles fixes
  that. What it would want is a render of the clothes on a body, which no
  on-device model can do and which sending a wardrobe to a server for is a
  different app. So an outfit is shown as the garments in it.

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

- [ ] Outfit planning calendar:
  - [ ] Plan an outfit by date
  - [ ] View upcoming planned outfits
- [ ] Restore preview: show what an archive contains before replacing anything.
      The validation it would read from already exists.
- [ ] Context-aware constraints, what is left of them: with the wear log gone,
      "not worn recently" is out for good, and what remains each needs a decision
      first -- a temperature band (see Weather, below) or a planned-outfit lookup
      (see the calendar, above). Nothing is unblocked here today.
- [ ] Weather, if it is wanted: there is no weather concept anywhere, not a field
      and not a constant, and `season` may be enough. A temperature band per
      garment plus one tap for "cold today" would keep this local-first; a forecast
      service would mean network, location and a key, which is a different app.

## Built, waiting on a device rather than on work

- [ ] **Cloud backup sync to Google Drive** -- PR #60, unmerged. Connect an
      account, back up, list what is there, restore any of it, disconnect.
      `drive.file` rather than `appDataFolder`, so a backup stays a zip its owner
      can open without this app. Two OAuth clients are registered and committed,
      against the release certificate's SHA-1
      (`c9c04a682b973e52b93edc82d5a39facfea438bf`) and the debug one.

      What is left is the one thing nothing in CI can do: **the sign-in has never
      run on a phone.** The browser round trip, the redirect scheme, the token
      exchange and the refresh are all unproven. That is the next step and it is a
      device step, not a coding one.

      One consequence to remember rather than rediscover: an Android OAuth client
      is keyed to the application id *and* the signing certificate. A new keystore
      means updating the release client's fingerprint in Google's console, or Drive
      sign-in breaks in release only -- debug keeps working and hides it. See
      Signing in the README.

## Parked

- [ ] **Scheduled backups**, which belong *after* the above rather than beside it:
      a weekly job writing a backup and keeping the last few. Deliberately not
      built yet -- a job that runs unattended wants the path it uses to have worked
      at least once while somebody was watching. Worth having on-device too, but a
      schedule whose only destination is a folder on the same phone is half a
      safety net.

## Suggested build order

1. Take PR #60 through a sign-in on a phone. It is the only thing between cloud
   backup and done, and no amount of work here substitutes for it.
2. Restore preview, which needs nothing new.
3. The planning calendar, now that an outfit can be put together by hand.
4. The backup schedule, once the path it would run unattended has worked once by
   hand.

What is left of the recommendation engine is waiting on product decisions rather
than on work: a temperature band, or a calendar to ask what is already planned.

The cutover to Kotlin is done, so each of these is built once, and the keystore it
was waiting on exists: published builds have been signed with a release key of
their own since 28 August 2026. Nothing here is blocked on infrastructure any
more -- what is left is either work or a product decision.
