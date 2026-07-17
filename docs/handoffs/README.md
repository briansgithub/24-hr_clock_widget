# Branch Handoff Index

Permanent, continuously updated branch handoffs. Read [MASTER_HANDOFF.md](MASTER_HANDOFF.md) first.

Each active branch has a replacement-style handoff plus an adjacent append-only history. Closed branches are **archived** (not deleted): see [archive/](archive/) and the global close-only ledger [HISTORY.md](HISTORY.md).

Last verified: 2026-07-17

| Branch | Tip/state | Role | Handoff | History |
|---|---|---|---|---|
| `main` | `2eaab30`; in sync with `origin/main` | Shared PR base | [main.md](main.md) | [history](main.history.md) |
| `feature/empirical-energy-logging` | `2983057` | Superseded intermediate; no PR | [handoff](feature/empirical-energy-logging.md) | [history](feature/empirical-energy-logging.history.md) |
| `feature/dynamic-sun-color` | `34b2b64` | First feature PR checkpoint | [handoff](feature/dynamic-sun-color.md) | [history](feature/dynamic-sun-color.history.md) |
| `feature/empirical-public-priority` | `b3fc1b5` + uncommitted handoff protocol updates | Second feature PR; current HEAD | [handoff](feature/empirical-public-priority.md) | [history](feature/empirical-public-priority.history.md) |

## Current merge order

1. ~~Publish `main` commit `2eaab30` to `origin/main`.~~ Done.
2. PR `feature/dynamic-sun-color` → `origin/main`.
3. Refresh `feature/empirical-public-priority` onto updated `origin/main`, then open its PR.
4. After the second feature PR merges: archive each handoff and adjacent history, append global HISTORY, then delete git branches for this branch and `feature/empirical-energy-logging`.

PRs target the shared remote `origin/main`. Before each PR, the responsible agent must propose automated/manual checks and ask the owner to approve or revise them.
