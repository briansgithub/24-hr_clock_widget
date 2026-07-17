# Branch Handoff Index

Permanent, continuously updated branch handoffs. Read [MASTER_HANDOFF.md](MASTER_HANDOFF.md) and [REPOSITORY.md](REPOSITORY.md) first.

Each active branch has a replacement-style handoff plus an adjacent append-only history. Closed branches are **archived** (not deleted): see [archive/](archive/) and the global close-only ledger [HISTORY.md](HISTORY.md).

Last verified: 2026-07-17

| Branch | Tip/state | Role | Handoff | History |
|---|---|---|---|---|
| `main` | in sync with `origin/main` after push | Shared integration branch | [main.md](main.md) | [history](main.history.md) |

## Current merge order

No open feature branches. Start new work from `origin/main`.

Recently archived: `chore/portable-handoff-kit` (merged). Earlier: `feature/dynamic-sun-color`, `feature/empirical-public-priority` (merged); `feature/empirical-energy-logging` (superseded).

New branches use [templates/](templates/). Validate with `python scripts/validate_handoffs.py`.
