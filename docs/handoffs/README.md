# Branch Handoff Index

Permanent, continuously updated branch handoffs. Read [MASTER_HANDOFF.md](MASTER_HANDOFF.md) first.

Each active branch has a replacement-style handoff plus an adjacent append-only history. Closed branches are **archived** (not deleted): see [archive/](archive/) and the global close-only ledger [HISTORY.md](HISTORY.md).

Last verified: 2026-07-17

| Branch | Tip/state | Role | Handoff | History |
|---|---|---|---|---|
| `main` | `339c651`; sync pending push to `origin/main` | Shared integration branch | [main.md](main.md) | [history](main.history.md) |

## Current merge order

No open feature PRs. Start new work from updated `origin/main` after this tip is pushed.

Recently archived: `feature/dynamic-sun-color`, `feature/empirical-public-priority` (merged); `feature/empirical-energy-logging` (superseded).
