# Branch: `main`

## Identity

- Tip: `origin/main` after post-merge archive cleanup (`90e2b49` plus this tip-sync commit)
- Upstream: `origin/main` (in sync after push)
- Role: shared feature-PR base / integration branch
- History: [main.history.md](main.history.md)
- Last verified: 2026-07-17

## Goal and scope

Maintain the stable integration branch for future feature work.

## Changes since previous published tip

- `4e8d4f2`: merged `feature/dynamic-sun-color` checkpoint.
- `339c651`: merged `feature/empirical-public-priority`.
- `90e2b49`: archived closed feature handoffs; active index is `main` only.

## Current status

- Working tree clean; only living branch is `main`.
- Feature git branches deleted locally and remotely.
- Ready for new `feature/` / `fix/` / `chore/` branches from `origin/main`.

## Next actions

1. Start new work from updated `origin/main`.
2. Create paired handoff + `.history.md` for each new branch.
3. Before each PR, propose automated/manual checks for owner approval.

## Merge and cleanup

- Readiness: current and published.
- `main.md` is never archived while `main` exists.
