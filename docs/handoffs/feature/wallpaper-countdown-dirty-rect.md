# Branch: `feature/wallpaper-countdown-dirty-rect`

## Identity

- Tip: dirty-rect 1 Hz wallpaper countdown + notification header cleanup (base `b135a9d`)
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

- Dirty-rect 1 Hz wallpaper countdown path; notification builder chronometer removed (`setShowWhen(false)`); branch handoff/index.

## Current status

- Working tree: clean after commit.
- Base relationship: ahead of `origin/main` `b135a9d`.
- Validation: `python scripts/validate_handoffs.py` passed. JDK (Studio JBR 21) now on user PATH. Awaiting owner device check.
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
