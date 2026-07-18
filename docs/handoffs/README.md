# Branch Handoff Index

Permanent, continuously updated branch handoffs. Read [MASTER_HANDOFF.md](MASTER_HANDOFF.md), [REPOSITORY.md](REPOSITORY.md), and [MULTI_AGENT.md](MULTI_AGENT.md) first.

Each active branch has a replacement-style handoff plus an adjacent append-only history. Closed branches are **archived** (not deleted): see [archive/](archive/) and the global close-only ledger [HISTORY.md](HISTORY.md).

Last verified: 2026-07-18

| Branch | Tip/state | Role | Handoff | History |
|---|---|---|---|---|
| `main` | ahead of `origin/main` at `da84193` | Shared integration branch | [main.md](main.md) | [history](main.history.md) |
| `feature/exercise-metric-help` | WIP in stash | Feature: Exercise HRSS/TRIMP/HRV help (paused) | [handoff](feature/exercise-metric-help.md) | [history](feature/exercise-metric-help.history.md) |
| `feature/display-tab-home-lock-ux` | ahead of main | Feature: Display Home/Lock tab UX | [handoff](feature/display-tab-home-lock-ux.md) | [history](feature/display-tab-home-lock-ux.history.md) |
| `feature/energy-entry-backup-drive` | ahead of main (WIP rebase) | Feature: Drive backup on energy entry | [handoff](feature/energy-entry-backup-drive.md) | [history](feature/energy-entry-backup-drive.history.md) |
| `audit-git-stash-state` | worktree at `ead09d4` | Audit/worktree (non-feature) | [handoff](audit-git-stash-state.md) | [history](audit-git-stash-state.history.md) |

## Current merge order

1. `feature/display-tab-home-lock-ux` → `main` (when ready)
2. `feature/energy-entry-backup-drive` → `main` (when rebase complete)
3. `feature/exercise-metric-help` → `main` (restore stash to resume)

Parallel agents must use separate Git worktrees — see [MULTI_AGENT.md](MULTI_AGENT.md).

Recently archived: `chore/multi-agent-worktree-docs`, `feature/empirical-log-jul16-cutoff` (merged). Earlier: `feature/timezone-mercator-map`, `feature/android-settings-ui-polish`, `feature/bedtime-countdown-10pm-floor`, `chore/portable-handoff-kit`, `feature/dynamic-sun-color`, `feature/empirical-public-priority` (merged); `feature/empirical-energy-logging` (superseded).

New branches use [templates/](templates/). Validate with `python scripts/validate_handoffs.py`.
