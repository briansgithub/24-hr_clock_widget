# Branch Handoff Index

Permanent, continuously updated branch handoffs. Read [MASTER_HANDOFF.md](MASTER_HANDOFF.md) first.

Last verified: 2026-07-17

| Branch | Tip/state | Role | Handoff |
|---|---|---|---|
| `main` | `2eaab30`; in sync with `origin/main` | Shared PR base | [main.md](main.md) |
| `feature/empirical-energy-logging` | `2983057` | Superseded intermediate; no PR | [feature/empirical-energy-logging.md](feature/empirical-energy-logging.md) |
| `feature/dynamic-sun-color` | `34b2b64` | First feature PR checkpoint | [feature/dynamic-sun-color.md](feature/dynamic-sun-color.md) |
| `feature/empirical-public-priority` | HEAD ≈ `b0f41ff` (substantive `2a1c25e`) | Second feature PR; current HEAD | [feature/empirical-public-priority.md](feature/empirical-public-priority.md) |

## Current merge order

1. ~~Publish `main` commit `2eaab30` to `origin/main`.~~ Done.
2. PR `feature/dynamic-sun-color` → `origin/main`.
3. Refresh `feature/empirical-public-priority` onto updated `origin/main`, then open its PR.
4. After the second feature PR merges, delete `feature/empirical-energy-logging`.

PRs target the shared remote `origin/main`. Before each PR, the responsible agent must propose automated/manual checks and ask the owner to approve or revise them.
