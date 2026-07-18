# Branch: `main`

## Identity

- Tip: `cfaa7aa` — Sync main handoff tip after bedtime archive cleanup.
- Upstream: `origin/main` (local ahead; push pending owner request)
- Role: shared feature-PR base / integration branch
- History: [main.history.md](main.history.md)
- Last verified: 2026-07-18

## Goal and scope

Maintain the stable integration branch for future feature work, with permanent handoff infrastructure in place.

## Changes since previous published tip

- `52444d9`: merged `feature/bedtime-countdown-10pm-floor` (22:00 floor + optional wallpaper countdown).
- `b4bbf8a` / `cfaa7aa`: archived bedtime handoff; refreshed active index.

## Current status

- Living branches: `main`, `feature/android-settings-ui-polish` (stashed WIP), `feature/energy-entry-backup-drive` (stashed WIP).
- Bedtime feature archived under `archive/feature/`.
- Local `main` is ahead of `origin/main`; not pushed yet.

## Next actions

1. Owner may push `main` when ready.
2. Resume stashed sibling features as needed.
3. Before each PR, propose automated/manual checks for owner approval.

## Merge and cleanup

- Readiness: merged locally; push deferred pending owner request.
- `main.md` is never archived while `main` exists.
