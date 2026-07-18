# Branch: main

## Identity

- Tip: pending archive commit after merges `2db20b3` / `3c2f7ca` onto `9dd5780`
- Upstream: `origin/main` (local ahead; push pending)
- Role: shared feature-PR base / integration branch
- History: [main.history.md](main.history.md)
- Last verified: `2026-07-18`

## Goal and scope

Maintain the stable integration branch for future feature work, with permanent handoff infrastructure and multi-agent worktree guidance in place.

## Changes since previous published tip

- Merged `feature/wallpaper-countdown-dirty-rect` (FF on `origin/main` at `9dd5780`).
- Merged `feature/timezone-map-lock-position` (`2db20b3`).
- Merged `chore/remove-preview-reset-button` (`3c2f7ca`).
- Archived all three branch handoffs/histories.

## Current status

- Clean `main` only after cleanup: no living feature/chore branches; no stashes.
- Primary worktree only after chore worktree removal.

## Next actions

1. Push `main` to `origin` when owner authorizes (merge cleanup already authorized).
2. Start the next feature from updated `origin/main` in a dedicated worktree when needed.

## Merge and cleanup

- N/A for `main` as integration branch.
