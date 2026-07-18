# History: `feature/bedtime-countdown-10pm-floor`

Append-only milestones for this branch. Current state and next actions belong in [bedtime-countdown-10pm-floor.md](bedtime-countdown-10pm-floor.md).

### 2026-07-17 — Branch created
- Commit/base: `main` at `b0187f4`
- Change: Created `feature/bedtime-countdown-10pm-floor` to clamp bedtime countdown targets to no earlier than 10:00 PM.
- Validation/decision: Scope recorded in the active handoff.

### 2026-07-17 — Implemented 22:00 floor
- Commit/base: `f7763b1`
- Change: After subtracting 90 minutes and rounding to 5 minutes, clamp time-of-day to 22:00 when earlier.
- Validation/decision: Owner confirmed keep 90-minute advance; committed on request.

### 2026-07-18 — Home/lock bedtime countdown overlay
- Commit/base: uncommitted
- Change: Added Display→Sleep `Bedtime Countdown` toggle (default off); draws H:MM:SS + target on wallpaper/preview. Placement: top-left when `smallTopRight`, else top-right. Shared `resolveBedtimeMillis` for notification + wallpaper.
- Validation/decision: Implemented from lock/home screenshots; awaiting owner visual check before commit.

### 2026-07-18 — Lowered countdown + battery-safe refresh
- Commit/base: uncommitted
- Change: Moved countdown to ~20% screen height (still top half). Removed 1s wallpaper/preview loop; countdown uses the normal 10s full-frame cadence. Notification chronometer remains the second-accurate surface.
- Validation/decision: Owner asked about battery cost of 1Hz redraws.

### 2026-07-18 — Home countdown nudge + right-justify
- Commit/base: uncommitted
- Change: On home (`smallTopRight`), right-justify Bedtime/countdown just left of the small dial and raise to ~14% height to clear the weather strip. Lock stays top-right justified.
- Validation/decision: Owner screenshot showed left-aligned overdue text overlapping weather.

### 2026-07-18 — Smaller top-right clock + countdown bottom align
- Commit/base: uncommitted
- Change: When `smallTopRight`, clock radius (and thus sun/moon orbit/icons) scaled to 90%. Countdown string bottom aligned to clock circumference bottom (`centerY + radius`).
- Validation/decision: Owner requested 10% shrink and vertical alignment with dial bottom.

### 2026-07-18 — Flush countdown bottom + clear sun/moon
- Commit/base: uncommitted
- Change: Bottom-align countdown via `getTextBounds` against outer stroked circumference; shift right-align anchor left of sun/moon orbit extent so text clears celestial icons.
- Validation/decision: Owner reported misalignment and sun/moon interference.

### 2026-07-18 — Wind-down wedge
- Commit/base: uncommitted
- Change: Added `showWindDown` (Display→Sleep “Wind-down Wedge”; home default on, lock default off). Draws 90 min before main-sleep asleep edge with light→dark gray SweepGradient (inverse of grogginess).
- Validation/decision: Owner requested mirror of grogginess wedge before purple sleep arc.
