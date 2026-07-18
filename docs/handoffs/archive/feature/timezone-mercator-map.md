# Archived: `feature/timezone-mercator-map`

## Identity

- Final tip: `7908253` — Add equirectangular timezone world map with location dot.
- Merge commit / PR: `f0ee7c5` (local merge into `main`; no PR)
- History: [timezone-mercator-map.history.md](timezone-mercator-map.history.md)
- Closed: `2026-07-18`

## Goal and scope

Toggleable equirectangular world map under the clock with correlated timezone meridian and phone location dot (home default off, lock default on).

## What landed

- `showTimezoneMap` setting + Display toggle/glyph using `world_map_equirectangular.png`.
- `ClockRenderer` map/meridian/pure-white location dot; wallpaper and preview lat/lon wiring.
- `TimeZoneUtils` correlated-offset helpers + unit tests; related Display glyph polish on the branch tip.

## Validation

- `:app:compileDebugKotlin` and `TimeZoneUtilsTest` passed during development.
- Offline equirectangular spot-check: major cities on land; oceans empty.
- `python scripts/validate_handoffs.py` after archive cleanup.

## Disposition

- Status: merged into `main`
- Remote branch deleted: not applicable (never pushed)
- Local branch deleted: yes after archive commit
- Cleanup completed: yes
