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

- Wardrobe gaps: what the wardrobe cannot finish, and what would finish it. Two
  halves. Coverage is counted exactly -- the templates are known and a template's
  combinations are the product of its slots, so "one more pair of shoes completes
  36 work outfits" is arithmetic rather than an estimate. Which garment to want is
  sampled, by seeding a garment that does not exist into the suggestion engine:
  occasion and season are both derived from a garment's type, so a category, a
  type and a colour is a complete garment as far as scoring is concerned, and no
  price, shop or wear log is needed. A gap card shows the outfits it would finish,
  made of the reader's own clothes with one empty frame in them, and closes into
  the add form already filled in.

  Two decisions worth stating, so they are not rediscovered as bugs: an occasion
  nothing is dressed for is *not* a gap -- ranked purely by coverage the advice
  became a cocktail dress and a track suit for a wardrobe of casual clothes with
  no shoes -- and tied garment types are reported as ties, because with nothing
  rated heels, flats and loafers fill a work-shoe slot identically and naming one
  would be naming the order of GARMENT_CATEGORIES as advice.
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

- Near-duplicates, in both directions: the warning when a garment is added, and a
  sweep over the wardrobe on the statistics page. The scoring for the first already
  existed; what was missing was ever asking the question about garments already
  saved, so shirts added on different days had never been compared with each other.

  **The rule is: the same subcategory, in the same colours.** That is all of it.
  There is no score and no threshold.

  There used to be both -- a weighted average over tags, colour and size,
  renormalised over whichever had data, behind a threshold that moved from 0.81 to
  0.65 to 0.74 over three attempts to make it behave. It is worth knowing why that
  failed, because the instinct to reintroduce it will come back. Every complaint
  turned out to be categorical rather than a matter of degree: a jumper is not a
  t-shirt, a black and red shirt is not a red shirt, a size is what fits you rather
  than what a garment is. A number cannot express any of those. Worse, the
  renormalisation meant two garments of one colour with nothing else recorded scored
  exactly 1.0, which no threshold below 1.0 could reach and 1.0 excluded everything.

  What the rule costs, and it is not small: every navy t-shirt is now one group,
  whatever else distinguishes them. Its virtue is that it can be predicted without
  reading any of this.

  Three details that are easy to get wrong a second time:

  - Palettes must *correspond*, not overlap -- same count, each colour with a
    partner, a partner spent once claimed. Order is not part of it, since which of
    two colours dominates is a fact about the photograph.
  - The sweep buckets by category itself, because two categories can share a
    subcategory name and `findDuplicatesAmong` never looks at the category.
  - Groups are anchored rather than chained. Sharing a type is not transitive: a
    garment filed as both "T-Shirt" and "Vest" would otherwise link the two.

  A garment with no subcategory is not a duplicate of anything, including another
  with none, and nothing on screen says so. Deliberate: without knowing what a
  garment is there is no claim to make. If the section ever looks empty on a
  wardrobe that plainly has twins, missing types are the first thing to check.

- App settings in the backup, and a choice about restoring them. The wardrobe was
  always what a backup was for, and settings were deliberately left out on the
  grounds that they are facts about a phone -- which is still true, and is why
  restoring them is a box somebody ticks rather than something that happens to
  them. Off by default: a restore is usually a recovery, and the wardrobe is what
  the person came for.

  **`wardrobapp_drive` is not in it, and that is the point of the design.** The
  list in `AppSettings` is an allowlist rather than a denylist, because that file
  holds an OAuth refresh token for a Google account and an archive is a zip that
  gets uploaded, downloaded, copied and shared. Forgetting to add a file to an
  allowlist means a setting does not travel; forgetting to exclude one from a
  denylist means a credential ends up in somebody's Drive. Only the second is
  unrecoverable.

  Two things worth not rediscovering: values carry their type, because
  SharedPreferences is typed and reading a column count back as a Long throws at
  whichever screen asks for it as an Int; and **nothing in the settings reader
  throws** -- an unreadable theme must never cost somebody the photos in the same
  archive. The format version stayed at 3, so builds that predate this can still
  restore archives that have settings, ignoring them.

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
