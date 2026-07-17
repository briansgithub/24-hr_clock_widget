# Branch Handoff Index

Permanent, continuously updated branch handoffs. Read [MASTER_HANDOFF.md](MASTER_HANDOFF.md) and [REPOSITORY.md](REPOSITORY.md) first.

Each active branch has a replacement-style handoff plus an adjacent append-only history. Closed branches are **archived** (not deleted): see [archive/](archive/) and the global close-only ledger [HISTORY.md](HISTORY.md).

Last verified: 2026-07-17

| Branch | Tip/state | Role | Handoff | History |
|---|---|---|---|---|
| `main` | in sync with `origin/main` | Shared integration branch | [main.md](main.md) | [history](main.history.md) |
| `feature/bedtime-countdown-10pm-floor` | `f7763b1` 10pm floor | Feature: bedtime countdown earliest 22:00 | [handoff](feature/bedtime-countdown-10pm-floor.md) | [history](feature/bedtime-countdown-10pm-floor.history.md) |

## Current merge order

1. `feature/bedtime-countdown-10pm-floor` → `main` (when ready)

Recently archived: `chore/portable-handoff-kit` (merged). Earlier: `feature/dynamic-sun-color`, `feature/empirical-public-priority` (merged); `feature/empirical-energy-logging` (superseded).

New branches use [templates/](templates/). Validate with `python scripts/validate_handoffs.py`.
