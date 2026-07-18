# Branch Handoff Index

Permanent, continuously updated branch handoffs. Read [MASTER_HANDOFF.md](MASTER_HANDOFF.md), [REPOSITORY.md](REPOSITORY.md), and [MULTI_AGENT.md](MULTI_AGENT.md) first.

Each active branch has a replacement-style handoff plus an adjacent append-only history. Closed branches are **archived** (not deleted): see [archive/](archive/) and the global close-only ledger [HISTORY.md](HISTORY.md).

Last verified: 2026-07-18

| Branch | Tip/state | Role | Handoff | History |
|---|---|---|---|---|
| `main` | `da84193` = `origin/main` | Shared integration branch | [main.md](main.md) | [history](main.history.md) |
| `feature/energy-entry-backup-drive` | `55ff716` — smoke PASSED; ready to push/PR | Feature: Drive backup on energy entry | [handoff](feature/energy-entry-backup-drive.md) | [history](feature/energy-entry-backup-drive.history.md) |
| `feature/display-tab-home-lock-ux` | product `9740fd8` + merge of main (uncommitted) | Feature: Display Home/Lock tab UX | [handoff](feature/display-tab-home-lock-ux.md) | [history](feature/display-tab-home-lock-ux.history.md) |
| `feature/exercise-metric-help` | FF to `da84193` + uncommitted metric-help restore | Feature: Exercise HRSS/TRIMP/HRV help | [handoff](feature/exercise-metric-help.md) | [history](feature/exercise-metric-help.history.md) |
| `audit-git-stash-state` | tip `ead09d4`; cleanup candidate (do not merge) | Audit/worktree | [handoff](audit-git-stash-state.md) | [history](audit-git-stash-state.history.md) |

## Current merge order

1. `feature/energy-entry-backup-drive` → `main` (smoke PASSED; push then PR when owner authorizes).
2. `feature/display-tab-home-lock-ux` → `main` (finish merge commit; owner visual check; then push/PR).
3. `feature/exercise-metric-help` → `main` (owner visual check; commit; then push/PR).

Parallel agents must use separate Git worktrees — see [MULTI_AGENT.md](MULTI_AGENT.md).

Recently archived: `chore/multi-agent-worktree-docs`, `feature/empirical-log-jul16-cutoff` (merged). Earlier: `feature/timezone-mercator-map`, `feature/android-settings-ui-polish`, `feature/bedtime-countdown-10pm-floor`, `chore/portable-handoff-kit`, `feature/dynamic-sun-color`, `feature/empirical-public-priority` (merged); `feature/empirical-energy-logging` (superseded).

New branches use [templates/](templates/). Validate with `python scripts/validate_handoffs.py`.
