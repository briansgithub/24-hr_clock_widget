# Branch Handoff Index

Permanent, continuously updated branch handoffs. Read [MASTER_HANDOFF.md](MASTER_HANDOFF.md), [REPOSITORY.md](REPOSITORY.md), and [MULTI_AGENT.md](MULTI_AGENT.md) first.

Each active branch has a replacement-style handoff plus an adjacent append-only history. Closed branches are **archived** (not deleted): see [archive/](archive/) and the global close-only ledger [HISTORY.md](HISTORY.md).

Last verified: 2026-07-18

| Branch | Tip/state | Role | Handoff | History |
|---|---|---|---|---|
| `main` | `cd755b3` = `origin/main` | Shared integration branch | [main.md](main.md) | [history](main.history.md) |
| `feature/exercise-metric-help` | `e041101` — ready to push/PR | Feature: Exercise HRSS/TRIMP/HRV help | [handoff](feature/exercise-metric-help.md) | [history](feature/exercise-metric-help.history.md) |
| `audit-git-stash-state` | tip `ead09d4`; cleanup candidate (do not merge) | Audit/worktree | [handoff](audit-git-stash-state.md) | [history](audit-git-stash-state.history.md) |

## Current merge order

1. `feature/exercise-metric-help` → `main` (push/PR when authorized).

Parallel agents must use separate Git worktrees — see [MULTI_AGENT.md](MULTI_AGENT.md).

Recently archived: `feature/display-tab-home-lock-ux` (merged via [PR #2](https://github.com/briansgithub/24-hr_clock_widget/pull/2)), `feature/energy-entry-backup-drive` (merged via [PR #1](https://github.com/briansgithub/24-hr_clock_widget/pull/1)). Earlier: `chore/multi-agent-worktree-docs`, `feature/empirical-log-jul16-cutoff`, `feature/timezone-mercator-map`, `feature/android-settings-ui-polish`, `feature/bedtime-countdown-10pm-floor`, `chore/portable-handoff-kit`, `feature/dynamic-sun-color`, `feature/empirical-public-priority` (merged); `feature/empirical-energy-logging` (superseded).

New branches use [templates/](templates/). Validate with `python scripts/validate_handoffs.py`.
