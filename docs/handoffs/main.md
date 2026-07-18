# Branch: main

## Identity

- Tip: `cd755b3` — Sync main handoff tip after display archive.
- Upstream: `origin/main` (matches)
- Role: shared feature-PR base / integration branch
- History: [main.history.md](main.history.md)
- Last verified: `2026-07-18`

## Goal and scope

Maintain the stable integration branch for future feature work, with permanent handoff infrastructure and multi-agent worktree guidance in place.

## Changes since previous published tip

- `cd4f97d` / `354a8db` / `9740fd8`: Display Home/Lock UX — remove Preview button; tab-scoped Reset; Home/Lock icons.
- `eeb27c4` / `7239aa0` / energy PR #1: Drive backup on energy entry; handoff archive.
- Earlier: multi-agent docs, Jul 16 empirical cutoff, timezone map, settings UI polish, wind-down gradient, bedtime countdown.

## Current status

- Display and energy features merged and archived.
- Living feature: `feature/exercise-metric-help` at `e041101` (visual check passed; push/PR in progress).
- `audit-git-stash-state`: tip `ead09d4`; do not merge; optional owner cleanup.
- Stashes: superseded main inventory stash; exercise stash until owner drops after merge.

## Next actions

1. Finish exercise push/PR/merge cleanup.
2. Owner: decide whether to delete `audit-git-stash-state` and drop superseded stashes.

## Merge and cleanup

- N/A for `main` as integration branch.
