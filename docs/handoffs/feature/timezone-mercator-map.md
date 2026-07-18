# Branch: `feature/timezone-mercator-map`

## Identity

- Tip: `2a3614f` — Archive settings-ui-polish handoffs after merge. (uncommitted timezone world-map implementation)
- Parent/base: `main` at `2a3614f`
- Upstream: none
- Role: feature
- History: [timezone-mercator-map.history.md](timezone-mercator-map.history.md)
- Last verified: `2026-07-18`

## Goal and scope

- Goal: Toggleable equirectangular world map just below the clock (clears sun/moon orbit) with a vertical meridian at the correlated wake-up timezone longitude and a tiny white dot at the phone’s current lat/lon — so horizontal gap ≈ longitude wake-shift.
- Included: `showTimezoneMap` (home off, lock on); `world_map_equirectangular.png`; `ClockRenderer` draw + equirectangular projection; Display toggle/glyph; `TimeZoneUtils` helpers; device location dot.
- Excluded: Mercator / decorative non-georeferenced assets; coupling map visibility to wake-info text.
- Acceptance criteria: Lock default on / home off; meridian and phone dot share lon→x scale; map below sun/moon; wake-info readable when both on; known cities land on land in offline check.

## Changes since branch creation

- Uncommitted: equirectangular drawable (replaced decorative “mercator” PNG); projection math fix; settings/renderer/wallpaper/preview wiring; Display “Timezone World Map” toggle + glyph; handoffs.

## Current status

- Working tree: dirty (timezone map + mid-size location dot + Subtract awake + bathy/acro glyphs).
- Base relationship: tip equals `main` @ `2a3614f`.
- Validation: handoffs validated; owner visual check pending for Subtract awake, phase glyphs, map.
- Risks/blockers: existing lock JSON without `showTimezoneMap` deserializes to `false` until toggle/reset (DEFAULT_LOCK is `true` for fresh/reset). Dot omitted if lat/lon unavailable. `showTotalBedtime` storage unchanged — UI switch is inverted (on = asleep-only).
- Stashes: none related.

## Next actions

1. Owner rebuild / visual check (Sun/Moon crescent glyph; Subtract awake; bathy/acro; map dot; map).
2. Commit when requested.

## Merge and cleanup

- PR base: `main`
- Readiness: implementation done; awaiting owner visual check + commit
- Required predecessor: none
- After merge: append closure history, archive this file and its history, update global HISTORY/index, then delete local/remote branch after reachability verification.
