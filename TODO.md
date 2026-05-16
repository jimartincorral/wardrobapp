# Wardrobapp Product To-Do

## Phase 1 - Quick Wins (1-2 days)

- [ ] Add advanced wardrobe filters in `app/(tabs)/wardrobe.tsx`:
  - [ ] `season`, `weather`, `occasion`, `size`, `brand`
  - [ ] Sort options: `newest`, `most worn`, `least worn`, `price high/low`
- [ ] Surface existing analytics from `src/services/analytics-service.ts` in `app/(tabs)/analytics.tsx`:
  - [ ] Monthly wear trend
  - [ ] Cost-per-wear highlights
  - [ ] Garment lifespan stats
- [ ] Improve empty states and nudges:
  - [ ] Outfits empty state with setup guidance
  - [ ] Analytics empty state with first actions
- [ ] Add favorite/pin outfits support:
  - [ ] DB flag for pinned outfits
  - [ ] UI to pin/unpin and show pinned first

## Phase 2 - Core UX Expansion (1 sprint)

- [ ] Build manual outfit builder:
  - [ ] Select garments by category
  - [ ] Preview combination
  - [ ] Save custom outfit
- [ ] Add outfit editing in `app/outfit/[id].tsx`:
  - [ ] Rename outfit
  - [ ] Replace/remove garments
  - [ ] Update occasion/season metadata
- [ ] Add outfit planning calendar:
  - [ ] Plan outfit by date
  - [ ] Mark planned outfit as worn
  - [ ] View upcoming planned outfits
- [ ] Add re-engagement nudges:
  - [ ] Unused-item prompts (90+ days)
  - [ ] Optional weekly wear reminders

## Phase 3 - Platform Features (2-4 sprints)

- [ ] Improve backup/restore reliability:
  - [ ] Versioned backup schema
  - [ ] Restore preview + validation
  - [ ] Migration safety checks
- [ ] Recommendation engine v2:
  - [ ] Better personalization signals
  - [ ] Context-aware constraints (weather/recent wear)
  - [ ] Explainable suggestion reasons in UI
- [ ] Add notifications and routines:
  - [ ] Plan-for-tomorrow reminder
  - [ ] Rotation reminder for low-use items

## Suggested Build Order

1. Advanced filters + analytics surfacing
2. Manual outfit builder
3. Planning calendar
4. Recommendation v2 + notifications
