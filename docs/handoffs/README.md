# Branch Handoff Index

Permanent, continuously updated branch handoffs. Read [MASTER_HANDOFF.md](MASTER_HANDOFF.md) and [REPOSITORY.md](REPOSITORY.md) first.

Each active branch has a replacement-style handoff plus an adjacent append-only history. Closed branches are **archived** (not deleted): see [archive/](archive/) and the global close-only ledger [HISTORY.md](HISTORY.md).

Last verified: 2026-07-18

| Branch | Tip/state | Role | Handoff | History |
|---|---|---|---|---|
| `main` | in sync with `origin/main` @ `b0187f4` | Shared integration branch | [main.md](main.md) | [history](main.history.md) |
| `feature/bedtime-countdown-10pm-floor` | uncommitted overlay | Feature: bedtime 22:00 floor + wallpaper countdown + wind-down wedge | [handoff](feature/bedtime-countdown-10pm-floor.md) | [history](feature/bedtime-countdown-10pm-floor.history.md) |
| `feature/android-settings-ui-polish` | WIP in `stash@{0}` | Feature: settings UI polish (paused) | [handoff](feature/android-settings-ui-polish.md) | [history](feature/android-settings-ui-polish.history.md) |
| `feature/energy-entry-backup-drive` | WIP in `stash@{1}` | Feature: Drive backup on energy entry (paused) | [handoff](feature/energy-entry-backup-drive.md) | [history](feature/energy-entry-backup-drive.history.md) |

## Current merge order

1. `feature/bedtime-countdown-10pm-floor` → `main` (when ready)
2. `feature/android-settings-ui-polish` → `main` (independent; restore stash)
3. `feature/energy-entry-backup-drive` → `main` (independent; restore stash)

Recently archived: `chore/portable-handoff-kit` (merged). Earlier: `feature/dynamic-sun-color`, `feature/empirical-public-priority` (merged); `feature/empirical-energy-logging` (superseded).

New branches use [templates/](templates/). Validate with `python scripts/validate_handoffs.py`.
