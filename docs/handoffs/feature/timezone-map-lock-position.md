# Branch: `feature/timezone-map-lock-position`

## Identity

- Tip: `7e510e0` — Fix timezone map to stay at lock-screen position on home and lock
- Parent/base: `origin/main` at `9dd5780`
- Upstream: none yet
- Role: feature
- History: [timezone-map-lock-position.history.md](timezone-map-lock-position.history.md)
- Last verified: `2026-07-18`

## Goal and scope

- Goal: When Timezone World Map is on, place the map in the lock-screen (large centered clock) position on both home and lock, whether or not Small Clock in Top-Right is on.
- Included: `ClockRenderer.drawTimezoneMap` layout anchor independent of active dial/`smallTopRight`.
- Excluded: Map sizing/content; countdown; wake-info text; other Display toggles.
- Acceptance criteria: Home and lock with map on show the same map Y position as lock with large centered clock; toggling Small Clock in Top-Right does not move the map.

## Changes since branch creation

- `7e510e0`: `drawTimezoneMap` computes large-centered radius/`centerY` from canvas size instead of the active dial metrics.

## Current status

- Working tree: clean after tip-SHA docs commit.
- Base relationship: ahead of `origin/main` `9dd5780` by 2 (after tip sync).
- Validation: `python scripts/validate_handoffs.py` passed; owner confirmed map position looks good on device.
- Risks/blockers: none known.
- Stashes: none.

## Next actions

1. Push/PR when owner authorizes.

## Merge and cleanup

- PR base: `main`
- Readiness: ready
- Required predecessor: none
- After merge: append closure history, archive this file and its history, update global HISTORY/index, then delete local/remote branch after reachability verification.
