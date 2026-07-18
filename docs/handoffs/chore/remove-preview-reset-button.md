# Branch: `chore/remove-preview-reset-button`

## Identity

- Tip: pending commit — remove Preview "Reset to defaults"
- Parent/base: `origin/main` at `9dd5780`
- Upstream: none yet
- Role: chore
- History: [remove-preview-reset-button.history.md](remove-preview-reset-button.history.md)
- Last verified: `2026-07-18`

## Goal and scope

- Goal: Remove the "Reset to defaults" control from the Preview tab only.
- Included: `ClockPreviewScreen` UI and its `onReset` wiring in `MainActivity`.
- Excluded: Display tab "Reset to Defaults"; Energy "Reset Model Defaults"; wallpaper preview overlay.
- Acceptance criteria: Preview shows Set Wallpaper (when enabled) without a reset button; Display/Energy reset buttons still present.

## Changes since branch creation

- This commit: Dropped Preview reset button, `onReset` parameter, and call-site reset of home/lock settings.

## Current status

- Working tree: clean after this commit (code + handoffs).
- Base relationship: branched from `origin/main` `9dd5780`; ahead by this commit once pushed.
- Validation: `python scripts/validate_handoffs.py` passed; Android compile not run in this environment.
- Risks/blockers: none known.
- Stashes: none.
- Worktree: `H:\Desktop\widgets\24-hr_clock_widget-wt-remove-preview-reset` (primary clone remains on `feature/timezone-map-lock-position`).

## Next actions

1. Owner: approve push/PR when ready.
2. Optional visual check: open Preview tab; confirm no reset button; confirm Display/Energy resets remain.

## Merge and cleanup

- PR base: `main`
- Readiness: ready after owner review
- Required predecessor: none
- After merge: append closure history, archive this file and its history, update global HISTORY/index, then delete local/remote branch after reachability verification.
