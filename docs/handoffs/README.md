# Branch Handoff Index

Permanent, continuously updated branch handoffs. Read [MASTER_HANDOFF.md](MASTER_HANDOFF.md), [REPOSITORY.md](REPOSITORY.md), and [MULTI_AGENT.md](MULTI_AGENT.md) first.

Each active branch has a replacement-style handoff plus an adjacent append-only history. Closed branches are **archived** (not deleted): see [archive/](archive/) and the global close-only ledger [HISTORY.md](HISTORY.md).

Last verified: 2026-07-18

| Branch | Tip/state | Role | Handoff | History |
|---|---|---|---|---|
| `main` | `3b5bb18` = `origin/main` | Shared integration branch | [main.md](main.md) | [history](main.history.md) |
| `feature/display-tab-lock-first` | `9e43fcc` / [PR #4](https://github.com/briansgithub/24-hr_clock_widget/pull/4) | Display Lock-first tabs | [handoff](feature/display-tab-lock-first.md) | [history](feature/display-tab-lock-first.history.md) |

## Current merge order

1. `feature/display-tab-lock-first`

Parallel agents must use separate Git worktrees — see [MULTI_AGENT.md](MULTI_AGENT.md).

Recently archived: `audit-git-stash-state` (closed-without-merge), `feature/exercise-metric-help` (merged via [PR #3](https://github.com/briansgithub/24-hr_clock_widget/pull/3)), `feature/display-tab-home-lock-ux` (merged via [PR #2](https://github.com/briansgithub/24-hr_clock_widget/pull/2)), `feature/energy-entry-backup-drive` (merged via [PR #1](https://github.com/briansgithub/24-hr_clock_widget/pull/1)). Earlier: `chore/multi-agent-worktree-docs`, `feature/empirical-log-jul16-cutoff`, `feature/timezone-mercator-map`, `feature/android-settings-ui-polish`, `feature/bedtime-countdown-10pm-floor`, `chore/portable-handoff-kit`, `feature/dynamic-sun-color`, `feature/empirical-public-priority` (merged); `feature/empirical-energy-logging` (superseded).

New branches use [templates/](templates/). Validate with `python scripts/validate_handoffs.py`.
