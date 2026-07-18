# Branch: main

## Identity

- Tip: `eeb27c4` — Finalize main handoff tip after energy archive push.
- Upstream: `origin/main` (matches)
- Role: shared feature-PR base / integration branch
- History: [main.history.md](main.history.md)
- Last verified: `2026-07-18`

## Goal and scope

Maintain the stable integration branch for future feature work, with permanent handoff infrastructure and multi-agent worktree guidance in place.

## Changes since previous published tip

- `eeb27c4` / `85f3e13` / `7239aa0`: archived energy handoffs; deleted local/remote `feature/energy-entry-backup-drive`.
- `c4de4b2` / `ff1c762` / `55ff716`: energy entry → Documents CSV → Google Drive upload; prompt datetime label; MISSED seeds skip Drive.
- Earlier: multi-agent docs, Jul 16 empirical cutoff, timezone map, settings UI polish, wind-down gradient, bedtime countdown.

## Current status

- Energy feature merged via PR #1; handoff archived; local/remote feature branch deleted.
- Living features: display (merged onto current main in worktree; visual check pending); exercise (metric-help restored uncommitted).
- `audit-git-stash-state`: tip `ead09d4`; do not merge; optional owner cleanup.
- Stash on primary: `wip main: pre-energy-archive inventory handoffs` (superseded — safe to drop when owner confirms).

## Next actions

1. Owner: display visual check; then authorize push/PR.
2. Owner: exercise visual check + commit; then push/PR.
3. Owner: decide whether to delete `audit-git-stash-state` and drop the superseded primary stash.

## Merge and cleanup

- N/A for `main` as integration branch.
