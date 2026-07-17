# Branch: `main`

## Identity

- Tip: `origin/main` after portable-handoff-kit merge and archive cleanup
- Upstream: `origin/main`
- Role: shared feature-PR base / integration branch
- History: [main.history.md](main.history.md)
- Last verified: 2026-07-17

## Goal and scope

Maintain the stable integration branch for future feature work, with permanent handoff infrastructure in place.

## Changes since previous published tip

- `bc0fad3`: merged `chore/portable-handoff-kit` (templates, AGENTS.md, validator, CI).
- Archive cleanup: closed chore handoff moved under `archive/chore/`.

## Current status

- Only living branch is `main`.
- Portable handoff kit installed and validated.
- Ready for new `feature/` / `fix/` / `chore/` branches from `origin/main`.

## Next actions

1. Start new work from updated `origin/main`.
2. Copy templates into mirrored handoff + `.history.md` for each new branch.
3. Before each PR, propose automated/manual checks for owner approval.

## Merge and cleanup

- Readiness: current and published after push.
- `main.md` is never archived while `main` exists.
