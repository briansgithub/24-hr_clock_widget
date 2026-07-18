# History: `feature/timezone-map-lock-position`

Append-only milestones for this branch. Current state and next actions belong in [timezone-map-lock-position.md](timezone-map-lock-position.md).

### 2026-07-18 — Branch created
- Commit/base: `origin/main` at `9dd5780`
- Change: Created `feature/timezone-map-lock-position` so the timezone world map always uses the lock-screen (large centered) vertical anchor on home and lock, regardless of Small Clock in Top-Right.
- Validation/decision: Scope and acceptance criteria recorded in the active handoff.

### 2026-07-18 — Fixed map layout anchor
- Commit/base: `7e510e0`
- Change: `ClockRenderer.drawTimezoneMap` no longer takes active dial `centerY`/`radius`; it derives the large-centered layout from canvas width/height so small-top-right home no longer pulls the map upward.
- Validation/decision: Owner confirmed map position looks good on device.

### 2026-07-18 — Merged into main
- Commit/base: tip `8073713`; merge `2db20b3`
- Change: Timezone map lock-screen vertical anchor on home and lock landed on `main`.
- Validation/decision: Owner authorized merge-all open branches; handoff archived.
