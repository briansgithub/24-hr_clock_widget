# Branch: main

## Identity

- Tip: (this commit) — Document Android Studio worktree workflow; drop obsolete stashes
- Upstream: `origin/main` (ahead until push)
- Role: shared feature-PR base / integration branch
- History: [main.history.md](main.history.md)
- Last verified: `2026-07-18`

## Goal and scope

Maintain the stable integration branch for future feature work, with permanent handoff infrastructure and multi-agent worktree guidance in place.

## Changes since previous published tip

- Android Studio worktree section in `MULTI_AGENT.md` + `REPOSITORY.md` pointer.
- `8016072` / `4ca26e5`: exercise archive after PR #3.
- Earlier: display PR #2, energy PR #1.

## Current status

- Energy, display, and exercise features merged and archived (PRs #1–#3).
- Living non-feature: `audit-git-stash-state` (do not merge; optional delete).
- Stashes: none (Android Studio docs committed; superseded inventory stash dropped).

## Next actions

1. Owner: decide whether to delete `audit-git-stash-state` local/remote branch.

## Merge and cleanup

- N/A for `main` as integration branch.
