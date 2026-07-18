# Branch: `feature/display-tab-lock-first`

## Identity

- Tip: pending gray-reset commit on `687cf93`
- Feature tip: `d1f3e29` — feat: default Display tab to Lock Screen first
- Parent/base: `main` / `origin/main` at `3b5bb18`
- Upstream: `origin/feature/display-tab-lock-first`
- PR: [#4](https://github.com/briansgithub/24-hr_clock_widget/pull/4)
- Role: feature
- History: [display-tab-lock-first.history.md](display-tab-lock-first.history.md)
- Last verified: `2026-07-18`

## Goal and scope

- Goal: Prefer Lock Screen in the Display tab switcher; light-gray Reset buttons.
- Included: Lock-first Display tabs; light-gray “Reset to Defaults” / preview reset styling.
- Excluded: Model reset styling; settings content changes.
- Acceptance criteria: Lock Screen leftmost + default; Reset buttons light gray.

## Changes since branch creation

- `d1f3e29`: Lock Screen tab first; default lock settings.
- Uncommitted: light-gray Reset buttons (Display + preview overlay).
- Handoff/PR babysit commits through `687cf93`.

## Current status

- Working tree: dirty — gray reset styling + handoffs; owner said visual check good; merge authorized after this commit.
- Validation: prior CI green; `python scripts/validate_handoffs.py` pending before push.
- Stashes: none.

## Next actions

1. Commit, push, merge PR #4 (`--merge` / no-ff).
2. After merge: archive handoff/history, update HISTORY/index/main, delete local/remote branch.

## Merge and cleanup

- PR base: `main` — [#4](https://github.com/briansgithub/24-hr_clock_widget/pull/4)
- Readiness: ready (owner authorized merge)
- Proposed PR checks: handoff validate; owner device visual — owner confirmed good.
- After merge: archive, HISTORY, index, branch delete.
