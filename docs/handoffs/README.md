# Branch Handoff Index

Active branch inventory and merge order. Consult the linked handoff when resuming that branch; detailed lifecycle and multi-agent guides are conditional references, not required startup reading.

Each active branch has a replacement-style handoff plus an adjacent append-only history. Closed branches are **archived** (not deleted): see [archive/](archive/) and the global close-only ledger [HISTORY.md](HISTORY.md).

Last verified: 2026-11-05

| Branch | Tip/state | Role | Handoff | History |
|---|---|---|---|---|
| `main` | `99756fe` (local) | Shared integration branch | [main.md](main.md) | [history](main.history.md) |
| `fix/pixel-launcher-surface` | See Git | Pixel Launcher wallpaper compatibility | [handoff](fix/pixel-launcher-surface.md) | [history](fix/pixel-launcher-surface.history.md) |

## Current merge order

`fix/pixel-launcher-surface` merges into local `main` after the debug unit tests and assemble pass.

Parallel agents must use separate Git worktrees — see [MULTI_AGENT.md](MULTI_AGENT.md).

Recently archived: `fix/fitbit-sleep-sync` (fast-forward into `main` at `68b6ff8`, 2026-11-05), `feature/energy-refit-aug09` (merged into `main` at `477d4c2`, 2026-11-05), `feature/awake-energy-model` (merged into `main` at `393c533` / ff, 2026-09-15), `feature/personal-energy-model-m4` (merged into `main` at `62cb343`, 2026-07-31), `feature/fold-calendar-into-display` (merged into `main` at `43f0872`, 2026-07-22), `fix/bedtime-countdown-sleep-default` (merged into `main` 2026-07-18), `feature/wallpaper-countdown-dirty-rect`, `feature/timezone-map-lock-position`, `chore/remove-preview-reset-button` (merged into `main` 2026-07-18), `feature/display-tab-lock-first` (merged via [PR #4](https://github.com/briansgithub/24-hr_clock_widget/pull/4) + [PR #5](https://github.com/briansgithub/24-hr_clock_widget/pull/5)), `audit-git-stash-state` (closed-without-merge), `feature/exercise-metric-help` (merged via [PR #3](https://github.com/briansgithub/24-hr_clock_widget/pull/3)), `feature/display-tab-home-lock-ux` (merged via [PR #2](https://github.com/briansgithub/24-hr_clock_widget/pull/2)), `feature/energy-entry-backup-drive` (merged via [PR #1](https://github.com/briansgithub/24-hr_clock_widget/pull/1)). Earlier: `chore/multi-agent-worktree-docs`, `feature/empirical-log-jul16-cutoff`, `feature/timezone-mercator-map`, `feature/android-settings-ui-polish`, `feature/bedtime-countdown-10pm-floor`, `chore/portable-handoff-kit`, `feature/dynamic-sun-color`, `feature/empirical-public-priority` (merged); `feature/empirical-energy-logging` (superseded).

New branches use [templates/](templates/). Agent guides: [../ai_agent_guides/README.md](../ai_agent_guides/README.md). Validate with `python scripts/validate_handoffs.py`.
