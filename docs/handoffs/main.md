# Branch: main

## Identity

- Tip: `cd4f97d` — Merge pull request #2 from briansgithub/feature/display-tab-home-lock-ux
- Upstream: `origin/main` (local matches after pull; archive cleanup commit may be ahead until push)
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

- Display feature merged via PR #2; handoff archival + branch/worktree deletion in this cleanup.
- Living feature: `feature/exercise-metric-help` (metric-help restored uncommitted in worktree).
- `audit-git-stash-state`: tip `ead09d4`; do not merge; optional owner cleanup.
- Stashes: `wip main: pre-energy-archive inventory handoffs` (superseded); exercise stash still present until exercise lands.

## Next actions

1. Owner: exercise visual check + authorize commit/push/PR.
2. Owner: decide whether to delete `audit-git-stash-state` and drop superseded stashes.

## Merge and cleanup

- N/A for `main` as integration branch.
