# Archived: `feature/timezone-map-lock-position`

## Identity

- Final tip: `8073713` — docs: record tip SHA after timezone map position fix
- Feature tip: `7e510e0` — Fix timezone map to stay at lock-screen position on home and lock
- Merge / PR: `2db20b3` — Merge branch 'feature/timezone-map-lock-position'
- Parent/base: `origin/main` at `9dd5780`
- History: [timezone-map-lock-position.history.md](timezone-map-lock-position.history.md)
- Disposition: merged into `main`
- Last verified: `2026-07-18`

## Goal and scope

When Timezone World Map is on, keep the map at the lock-screen (large centered) vertical position on home and lock, independent of Small Clock in Top-Right.

## What landed

- `ClockRenderer.drawTimezoneMap` derives large-centered radius/`centerY` from canvas size instead of active dial metrics.

## Validation recorded

- `python scripts/validate_handoffs.py` passed.
- Owner confirmed map position looks good on device.
- Owner authorized merge of all open branches into `main`.

## Cleanup

- Local branch deleted: yes (cleanup after archive)
- Remote branch deleted: not applicable (never published)
- Cleanup completed: yes
