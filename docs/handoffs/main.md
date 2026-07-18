# Branch: main

## Identity

- Tip: `ead09d4` — Sync main handoff tip after timezone map archive.
- Upstream: `origin/main` (local ahead; push pending owner request)
- Role: shared feature-PR base / integration branch
- History: [main.history.md](main.history.md)
- Last verified: `2026-07-18`

## Goal and scope

Maintain the stable integration branch for future feature work, with permanent handoff infrastructure in place.

## Changes since previous published tip

- `52444d9`: merged bedtime countdown (22:00 floor + optional wallpaper countdown).
- `dea5ea7`: wind-down wedge gradient `#5C5C5C` → `#111111`.
- `c13f36a`: merged settings Switch toggles + Display element glyphs.
- `f0ee7c5`: merged equirectangular timezone world map + location dot.
- `ead09d4`: timezone map handoff archive + tip sync.

## Current status

- Living branches: `main`, `feature/display-tab-home-lock-ux` (active), `feature/energy-entry-backup-drive` (stashed WIP).
- Working tree: clean on `main` tip; Display UX work is on the feature branch.
- Local `main` ahead of `origin/main`; not pushed yet.

## Next actions

1. Owner may push `main` when ready.
2. Merge `feature/display-tab-home-lock-ux` when ready.
3. Resume stashed `feature/energy-entry-backup-drive` as needed.

## Merge and cleanup

- N/A for `main` as integration branch.
