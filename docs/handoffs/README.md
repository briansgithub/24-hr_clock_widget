# Branch Handoff Index

Permanent, continuously updated branch handoffs. Read [MASTER_HANDOFF.md](MASTER_HANDOFF.md) and [REPOSITORY.md](REPOSITORY.md) first.

Each active branch has a replacement-style handoff plus an adjacent append-only history. Closed branches are **archived** (not deleted): see [archive/](archive/) and the global close-only ledger [HISTORY.md](HISTORY.md).

Last verified: 2026-07-18

| Branch | Tip/state | Role | Handoff | History |
|---|---|---|---|---|
| `main` | ahead of `origin/main` after settings UI merge `c13f36a` | Shared integration branch | [main.md](main.md) | [history](main.history.md) |
| `feature/energy-entry-backup-drive` | WIP in stash | Feature: Drive backup on energy entry (paused) | [handoff](feature/energy-entry-backup-drive.md) | [history](feature/energy-entry-backup-drive.history.md) |
| `feature/timezone-mercator-map` | tip at `c13f36a`; WIP | Feature: timezone / Mercator map | [handoff](feature/timezone-mercator-map.md) | [history](feature/timezone-mercator-map.history.md) |

## Current merge order

1. `feature/timezone-mercator-map` → `main` (when ready)
2. `feature/energy-entry-backup-drive` → `main` (restore stash to resume)

Recently archived: `feature/android-settings-ui-polish` (merged). Earlier: `feature/bedtime-countdown-10pm-floor`, `chore/portable-handoff-kit`, `feature/dynamic-sun-color`, `feature/empirical-public-priority` (merged); `feature/empirical-energy-logging` (superseded).

New branches use [templates/](templates/). Validate with `python scripts/validate_handoffs.py`.
