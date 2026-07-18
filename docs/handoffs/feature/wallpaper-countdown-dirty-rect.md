# Branch: `feature/wallpaper-countdown-dirty-rect`

## Identity

- Tip: `75f5d9b` - Add battery-safe 1 Hz dirty-rect wallpaper countdown
- Parent/base: `main` at `b135a9d`
- Upstream: none yet
- Role: feature
- History: [wallpaper-countdown-dirty-rect.history.md](wallpaper-countdown-dirty-rect.history.md)
- Last verified: `2026-07-18`

## Goal and scope

- Goal: Tick wallpaper bedtime countdown every second via dirty-rect redraw without a full-scene 1 Hz loop; remove duplicate notification header chronometer.
- Included: `ClockRenderer` dirty bounds + partial draw; `ClockWallpaperService` visibility-gated 1 Hz dirty job (10 s full draw retained); `BedtimeCountdownService` header chronometer cleanup.
- Excluded: Changing full-scene cadence; 1 Hz settings preview; countdown placement/typography redesign.
- Acceptance criteria: Home/lock countdown seconds advance when toggle on and visible; dial/map still refresh ~10 s; no ghosting/dial erase; notification header shows Target without duplicate countdown; large body chronometer unchanged.

## Changes since branch creation

- `75f5d9b`: Dirty-rect 1 Hz wallpaper countdown path; notification builder chronometer removed (setShowWhen(false)); branch handoff/index.
- `224d05a`: Record tip SHA in handoff/index.

## Current status

- Working tree: clean after UTF-8 tip fix.
- Base relationship: ahead of `origin/main` `b135a9d`.
- Validation: validate_handoffs.py after this fix. JDK (Studio JBR 21) on user PATH. Awaiting owner device check.
- Risks/blockers: none known beyond owner visual confirmation.
- Stashes: none.

## Next actions

1. Owner: manual home/lock countdown + notification shade check.
2. Push/PR when owner authorizes.

## Merge and cleanup

- PR base: `main`
- Readiness: ready after owner visual checks
- Required predecessor: none
- After merge: append closure history, archive this file and its history, update global HISTORY/index, then delete local/remote branch after reachability verification.