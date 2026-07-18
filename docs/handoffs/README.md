# Branch Handoff Index

Permanent, continuously updated branch handoffs. Read [MASTER_HANDOFF.md](MASTER_HANDOFF.md), [REPOSITORY.md](REPOSITORY.md), and [MULTI_AGENT.md](MULTI_AGENT.md) first.

Each active branch has a replacement-style handoff plus an adjacent append-only history. Closed branches are **archived** (not deleted): see [archive/](archive/) and the global close-only ledger [HISTORY.md](HISTORY.md).

Last verified: 2026-07-18

| Branch | Tip/state | Role | Handoff | History |
|---|---|---|---|---|
| `main` | local may differ; `origin/main` = `9dd5780` | Shared integration branch | [main.md](main.md) | [history](main.history.md) |
| `chore/remove-preview-reset-button` | ahead of `origin/main` `9dd5780` | Remove Preview "Reset to defaults" button | [handoff](chore/remove-preview-reset-button.md) | [history](chore/remove-preview-reset-button.history.md) |
| `feature/wallpaper-countdown-dirty-rect` | living sibling (may be absent in this worktree) | Dirty-rect 1 Hz wallpaper countdown + notification header cleanup | [handoff](feature/wallpaper-countdown-dirty-rect.md) | [history](feature/wallpaper-countdown-dirty-rect.history.md) |

## Current merge order

1. `chore/remove-preview-reset-button`
2. `feature/wallpaper-countdown-dirty-rect`

Parallel agents must use separate Git worktrees — see [MULTI_AGENT.md](MULTI_AGENT.md).

Recently archived: `feature/display-tab-lock-first` (merged via [PR #4](https://github.com/briansgithub/24-hr_clock_widget/pull/4) + [PR #5](https://github.com/briansgithub/24-hr_clock_widget/pull/5)), `audit-git-stash-state` (closed-without-merge), `feature/exercise-metric-help` (merged via [PR #3](https://github.com/briansgithub/24-hr_clock_widget/pull/3)), `feature/display-tab-home-lock-ux` (merged via [PR #2](https://github.com/briansgithub/24-hr_clock_widget/pull/2)), `feature/energy-entry-backup-drive` (merged via [PR #1](https://github.com/briansgithub/24-hr_clock_widget/pull/1)). Earlier: `chore/multi-agent-worktree-docs`, `feature/empirical-log-jul16-cutoff`, `feature/timezone-mercator-map`, `feature/android-settings-ui-polish`, `feature/bedtime-countdown-10pm-floor`, `chore/portable-handoff-kit`, `feature/dynamic-sun-color`, `feature/empirical-public-priority` (merged); `feature/empirical-energy-logging` (superseded).

New branches use [templates/](templates/). Validate with `python scripts/validate_handoffs.py`.
