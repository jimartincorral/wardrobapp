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
- Backing up to Google Drive, and doing it on a schedule: connect an account, back
  up by hand or automatically, see what is in the folder, restore from any of it,
  disconnect. How often, how many to keep, whether to wait for Wi-Fi and whether to
  wait for charge are all chosen rather than assumed -- the first version decided
  all four on the reader's behalf, which was wrong.
- Looking inside a backup before restoring from it: an archive says when it was
  made and how many photos it holds, and one this build cannot read is refused
  before anybody has committed to it rather than after. The Drive list is dated
  rather than named for the same reason -- a timestamped file name is a date nobody
  reads at a glance.

- Near-duplicates, in both directions. The scoring and the warning when adding a
  garment were already there; what was missing was ever asking the question about
  garments already saved, so shirts added on different days had never been compared
  with each other. Statistics now sweeps the wardrobe and groups what it finds.

  Two decisions worth not rediscovering: groups are anchored rather than chained,
  because A resembling B and B resembling C does not make A resemble C and one
  absurd group costs the whole list its credibility; and the sweep buckets by
  category itself, because `findDuplicatesAmong` scores tags, colour and size and
  never looks at the category at all -- it is right at the add form only because
  that caller narrows the *query* first.

  The first version reported far too much, and the fix was not the threshold. The
  score renormalises over whichever signals have data, so two garments of the same
  colour with no tags scored exactly 1.0 and no threshold below 1.0 could reach
  them; season tags made it worse, since `mergeStructuredTags` folds seasons into
  the tags column and two garments both marked "summer" score a perfect Jaccard
  match on a filter value. So the same subcategory and the same colour are now
  conditions rather than signals, and the threshold stayed at 0.65.

  The threshold then moved 0.65 -> 0.74, which is derived rather than chosen: past
  the gates the only things left to disagree about are tags and size, and a pair
  differing only in a recorded size scores 0.750. Keeping those is the owner's
  decision, so the bar sits directly under it, and what it removes is partial tag
  overlap. Tests bracket it on both sides -- lowering fails the tag cases, raising
  fails the size one -- so the next person to move it is told what it costs.

  Then the colour gate became a *palette* gate: a black and red shirt is not a red
  shirt, and comparing only the dominant colour said it was, since red leads both
  palettes and nothing looked further. Palettes now have to correspond one to one,
  order aside -- which of two colours dominates is a fact about the photograph. And
  size stopped counting at all: the same shirt in an M and an L is the same shirt,
  and two different shirts that are both M are still two shirts.

  One trap that cost a round: the palette gate and the colour *score* have to agree
  about which colours they compare. The first version gated on a matched palette and
  then scored the leading colours positionally, so a reversed palette passed the
  gate and scored red against black. They are one call now.

  A garment with no subcategory is therefore not a duplicate of anything, and
  nothing on screen says so. Deliberate: without knowing what a garment is there is
  no claim to make. If the section ever looks empty on a wardrobe that plainly has
  twins, missing types are the first thing to check.

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

      **Held on purpose, not merely unstarted.** Nothing new is built until cloud
      backup works on a phone, because everything unproven there is unproven in the
      same way: written, reviewed, green in CI, and never once run. Adding a second
      such feature would double what is waiting on the same afternoon with a device
      in hand rather than halve it.

      Its product decisions are also still open -- whether a planned outfit is a
      date on an outfit or a table of its own, whether past plans become history or
      vanish, and whether "what am I wearing Thursday" is a screen or a section of
      one. Those shape the schema, and a schema is the expensive thing to get
      wrong.
- [ ] Context-aware constraints, what is left of them: with the wear log gone,
      "not worn recently" is out for good, and what remains each needs a decision
      first -- a temperature band (see Weather, below) or a planned-outfit lookup
      (see the calendar, above). Nothing is unblocked here today.
- [ ] Weather, if it is wanted: there is no weather concept anywhere, not a field
      and not a constant, and `season` may be enough. A temperature band per
      garment plus one tap for "cold today" would keep this local-first; a forecast
      service would mean network, location and a key, which is a different app.

## Parked

- [ ] **A local destination for the schedule.** The automatic backup goes to Drive
      only. A copy on the same phone is half a safety net -- it survives a mistake
      and not a lost phone -- but half is more than none, and it is the answer for
      somebody who will not connect a Google account at all.

## Suggested build order

1. **Watch one scheduled backup actually happen.** The sign-in, the upload and the
   restore have all been through a phone; the schedule has not. Its constraints,
   its interval and its cancellation are argued from the WorkManager API and
   nothing more, and the honest test is a fortnight of the app being installed.
2. The planning calendar, whose hold has lifted -- it was waiting on cloud backup
   working on a phone, and it does -- once its product questions are settled: whether a planned
   outfit is a date on an outfit or a table of its own, whether past plans become
   history or vanish, and whether "what am I wearing Thursday" is a screen or part
   of one. Those shape the schema, which is the expensive thing to get wrong.

What is left of the recommendation engine is waiting on product decisions rather
than on work: a temperature band, or a calendar to ask what is already planned.

The cutover to Kotlin is done, so each of these is built once, and the keystore it
was waiting on exists: published builds have been signed with a release key of
their own since 28 August 2026. Nothing here is blocked on infrastructure any
more -- what is left is either work or a product decision.
