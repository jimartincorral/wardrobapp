# Wardrobapp Product To-Do

Both apps are meant here unless a line says otherwise. The Kotlin port has reached
feature parity, so anything below lands in both until cutover — see the README.

## Done since this list was written

- Advanced wardrobe filters — `season`, `occasion`, `size`, `brand`, and sorting.
  In both apps.
- Favourite/pin outfits — the `is_pinned` column, `ORDER BY is_pinned DESC`, and
  pin/unpin in the UI. In both apps.
- Empty states for the wardrobe, outfits, analytics and statistics screens.
- Garment lifespan, surfaced on the analytics screen.
- Versioned backups with validation and migration safety: the format carries a
  version, a restore refuses an archive it cannot read and says why, and it stages,
  verifies and rolls back rather than overwriting in place.

## Blocked on one missing thing: a wear log

The app records outfit *ratings*, not what was worn on a given day. Four wanted
features all reduce to that, and none of them can be built without it:

- [ ] Monthly wear trend
- [ ] Cost-per-wear (also needs a price field, below)
- [ ] Sort the wardrobe by most worn / least worn
- [ ] Rotation reminders for low-use items

So the first decision is the wear log itself, not the features on top of it:

- [ ] Decide what a wear is — a tap on an outfit, a tap on a garment, or a date
      picker — and whether it is one row per garment or per outfit
- [ ] `worn` table, keyed on garment or outfit id plus a date
- [ ] One way to record it that does not feel like bookkeeping, or nobody will

## Blocked on fields that do not exist

- [ ] **Price.** No garment carries one. Needed for cost-per-wear and for sorting
      by price, both of which this list has asked for. Cheap to add to the schema;
      the question is whether it is asked for on the add form or left optional.
- [ ] **Weather.** This list asks for a weather filter, but there is no weather
      concept anywhere — not a field, not a constant. `season` is the nearest
      thing and may be enough. If it is not, it needs defining before it can be
      filtered on.

## Ready to build

- [ ] Manual outfit builder:
  - [ ] Select garments by category
  - [ ] Preview combination
  - [ ] Save custom outfit
- [ ] Outfit editing in `app/outfit/[id].tsx` and its Kotlin counterpart:
  - [ ] Rename outfit
  - [ ] Replace/remove garments
  - [ ] Update occasion/season metadata
- [ ] Outfit planning calendar:
  - [ ] Plan an outfit by date
  - [ ] Mark a planned outfit as worn — which is the wear log arriving through the
        back door, so decide that first
  - [ ] View upcoming planned outfits
- [ ] Restore preview: show what an archive contains before replacing anything.
      The validation it would read from already exists.
- [ ] Translate what goes wrong. Both apps are shipped in two languages, and the
      React Native one still answers a failure in English: 27 of the sentences it
      throws from `src/services` are literals, and the screens put them straight
      in front of the reader — `Alert.alert(t('...importTitle'), error.message)`
      on the add screen, `${t('...backupFailedMsg')}${getErrorMessage(error)}` on
      settings. The backup progress bar is the same, seven phase messages long.
      The Kotlin port already solved this by throwing a reason rather than a
      sentence (`ImportFailureReason`, `UnsafeUrlReason`), so there is a shape to
      copy. `src/i18n/hardcoded-strings.test.ts` guards the screens against this
      and deliberately does not cover the services, so this is not a check anyone
      is waiting on — it is a decision about whose job the wording is.
- [ ] Recommendation engine v2:
  - [ ] Better personalization signals
  - [ ] Context-aware constraints — the "recent wear" half needs the wear log
  - [ ] Explainable suggestion reasons in the UI
- [ ] Notifications and routines:
  - [ ] Plan-for-tomorrow reminder
  - [ ] Unused-item prompts (90+ days) — needs the wear log

## Suggested build order

1. Decide the wear log, then build it. Four features and half of another are
   waiting behind it, and every day without it is another day of history not
   recorded.
2. Manual outfit builder, then outfit editing — the two that need nothing new.
3. Planning calendar, which is where the wear log starts paying for itself.
4. Recommendation v2 and notifications.

Cutover to the Kotlin app is tracked in the README rather than here; it is not a
product feature, but it does decide how many places each of these has to be built.
