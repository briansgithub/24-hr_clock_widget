# History: `feature/wallpaper-countdown-dirty-rect`

Append-only milestones for this branch. Current state and next actions belong in [wallpaper-countdown-dirty-rect.md](wallpaper-countdown-dirty-rect.md).

### 2026-07-18 — Branch created
- Commit/base: `origin/main` at `b135a9d`
- Change: Created `feature/wallpaper-countdown-dirty-rect` for battery-safe 1 Hz dirty-rect wallpaper countdown and notification header chronometer cleanup.
- Validation/decision: Scope and acceptance criteria recorded in the active handoff.

### 2026-07-18 — Dirty-rect countdown + notification header fix
- Commit/base: branch tip (dirty-rect countdown feature commit)
- Change: Wallpaper keeps 10 s full redraws; when Bedtime Countdown is on and visible, a 1 Hz `lockCanvas(dirty)` path clears/redraws only the countdown stack (skips if bounds hit the dial). Notification builder no longer uses system chronometer/`showWhen` so the header no longer duplicates the large body timer.
- Validation/decision: `validate_handoffs.py` passed; awaiting owner device check.
