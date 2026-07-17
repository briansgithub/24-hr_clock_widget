# Branch: `main`

## Identity

- Tip: `2eaab30` — Adjust bathyphase and acrophase indicator sizing and z-order
- Upstream: `origin/main` (in sync)
- Role: shared feature-PR base
- History: [main.history.md](main.history.md)
- Last verified: 2026-07-17

## Goal and scope

Maintain the stable integration branch. Latest published change is Python bathyphase/acrophase indicator sizing and z-order.

## Changes since previous remote tip

- `2eaab30`: adjusted indicator sizing/z-order in `python/clock_widget.py` and `python/energy_logic.py`; published to `origin/main`.

## Current status

- Local `main` and `origin/main` match at `2eaab30`.
- Ready as the PR base for feature branches.

## Next actions

1. Accept checkpoint PR from `feature/dynamic-sun-color`.
2. Then accept PR from `feature/empirical-public-priority` after it refreshes onto this tip.
3. After each merge: pull `main`, append `main.history.md`, then ensure agents archive closed feature handoffs and their histories before deleting merged git branches.

## Merge and cleanup

- Readiness: published and current.
- Feature PRs should target `origin/main`.
- `main.md` itself is never archived while `main` exists.
