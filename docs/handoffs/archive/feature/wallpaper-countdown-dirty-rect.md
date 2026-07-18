# Archived: `feature/wallpaper-countdown-dirty-rect`

## Identity

- Final tip: `9dd5780` — docs: fix handoff UTF-8 encoding after tip sync
- Feature tip: `75f5d9b` — Add battery-safe 1 Hz dirty-rect wallpaper countdown
- Merge / PR: fast-forward onto `origin/main` at `9dd5780` (no `--no-ff` merge commit)
- Parent/base: `main` at `b135a9d`
- History: [wallpaper-countdown-dirty-rect.history.md](wallpaper-countdown-dirty-rect.history.md)
- Disposition: merged into `main`
- Last verified: `2026-07-18`

## Goal and scope

Battery-safe 1 Hz dirty-rect wallpaper bedtime countdown; remove duplicate notification header chronometer.

## What landed

- `ClockRenderer` dirty bounds + partial countdown draw; `ClockWallpaperService` visibility-gated 1 Hz dirty job (10 s full draw retained).
- `BedtimeCountdownService` notification header chronometer removed (`setShowWhen(false)`).

## Validation recorded

- `python scripts/validate_handoffs.py` passed on the feature branch.
- Owner authorized merge of all open branches into `main`.

## Cleanup

- Local branch deleted: yes (cleanup after archive)
- Remote branch deleted: not applicable (never published)
- Cleanup completed: yes
