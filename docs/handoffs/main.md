# Branch: main

## Identity

- Tip: `f6d625b` — Archive multi-agent docs chore and note compliant stashes.
- Upstream: `origin/main` (local ahead; push pending)
- Role: shared feature-PR base / integration branch
- History: [main.history.md](main.history.md)
- Last verified: `2026-07-18`

## Goal and scope

Maintain the stable integration branch for future feature work, with permanent handoff infrastructure and multi-agent worktree guidance in place.

## Changes since previous published tip

- `f6d625b`: archived chore handoff; compliant stash messages noted on paused features.
- `520511e`: multi-agent worktree protocol + lean anti-loss/stash ownership; worktree-aware validator ledger.
- `b2de326` / `af95262` / `10787ce`: Jul 16 empirical log cutoff and archive cleanup.
- Earlier: timezone map, settings UI polish, wind-down gradient, bedtime countdown.

## Current status

- Living: paused feature branches (compliant stashes + handoff notes), audit worktree.
- Working tree: clean after chore archive.
- Local `main` ahead of `origin/main`.

## Next actions

1. Owner may push `main` and living feature branches when ready.
2. Resume stashed feature branches via worktrees as needed (see [MULTI_AGENT.md](MULTI_AGENT.md)).

## Merge and cleanup

- N/A for `main` as integration branch.
