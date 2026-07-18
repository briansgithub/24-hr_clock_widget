# Branch: `feature/display-tab-lock-first`

## Identity

- Tip: `d1f3e29` — feat: default Display tab to Lock Screen first
- Parent/base: `main` / `origin/main` at `3b5bb18`
- Upstream: pending push
- Role: feature
- History: [display-tab-lock-first.history.md](display-tab-lock-first.history.md)
- Last verified: `2026-07-18`

## Goal and scope

- Goal: Prefer Lock Screen in the Display tab switcher.
- Included: Swap Home/Lock tab order; default selected tab to Lock Screen on Display entry.
- Excluded: Preview canvas Home/Lock toggle behavior; settings content changes.
- Acceptance criteria: Lock Screen tab is leftmost; opening Display shows Lock Screen settings.

## Changes since branch creation

- `d1f3e29`: `DisplaySettingsScreen` — Lock Screen tab first (index 0), Home Screen second; default maps to lock settings/update/reset; handoffs added.

## Current status

- Working tree: clean after feature commit; pushing for PR.
- Base relationship: one commit ahead of `origin/main` (`3b5bb18`).
- Validation: `python scripts/validate_handoffs.py` passed; device visual check pending owner.
- Risks/blockers: none known.
- Stashes: none.

## Next actions

1. Owner visual check on device (Display → Lock leftmost + default).
2. After merge: archive handoff/history, update global HISTORY/index, delete branch.

## Merge and cleanup

- PR base: `main`
- Readiness: ready after owner visual check
- Required predecessor: none
- Proposed PR checks: `python scripts/validate_handoffs.py`; manual Display-tab visual check on device.
- After merge: append closure history, archive this file and its history, update global HISTORY/index, then delete local/remote branch after reachability verification.
