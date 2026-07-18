# Branch Handoff Index

Permanent, continuously updated branch handoffs. Read [MASTER_HANDOFF.md](MASTER_HANDOFF.md) and [REPOSITORY.md](REPOSITORY.md) first.

Each active branch has a replacement-style handoff plus an adjacent append-only history. Closed branches are **archived** (not deleted): see [archive/](archive/) and the global close-only ledger [HISTORY.md](HISTORY.md).

Last verified: 2026-07-18

| Branch | Tip/state | Role | Handoff | History |
|---|---|---|---|---|
| `main` | ahead of `origin/main` at `ead09d4` | Shared integration branch | [main.md](main.md) | [history](main.history.md) |
| `feature/display-tab-home-lock-ux` | uncommitted WIP | Feature: Display Home/Lock tab UX | [handoff](feature/display-tab-home-lock-ux.md) | [history](feature/display-tab-home-lock-ux.history.md) |
| `feature/energy-entry-backup-drive` | WIP in stash | Feature: Drive backup on energy entry (paused) | [handoff](feature/energy-entry-backup-drive.md) | [history](feature/energy-entry-backup-drive.history.md) |

## Current merge order

1. `feature/display-tab-home-lock-ux` → `main`
2. `feature/energy-entry-backup-drive` → `main` (restore stash to resume)

Recently archived: `feature/timezone-mercator-map` (merged). Earlier: `feature/android-settings-ui-polish`, `feature/bedtime-countdown-10pm-floor`, `chore/portable-handoff-kit`, `feature/dynamic-sun-color`, `feature/empirical-public-priority` (merged); `feature/empirical-energy-logging` (superseded).

New branches use [templates/](templates/). Validate with `python scripts/validate_handoffs.py`.
