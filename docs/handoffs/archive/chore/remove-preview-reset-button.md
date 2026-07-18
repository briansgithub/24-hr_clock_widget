# Archived: `chore/remove-preview-reset-button`

## Identity

- Final tip: `441f50f` — docs: record tip SHA after Preview reset removal
- Feature tip: `a356b06` — Remove Preview tab Reset to defaults button
- Merge / PR: `3c2f7ca` — Merge branch 'chore/remove-preview-reset-button'
- Parent/base: `origin/main` at `9dd5780`
- History: [remove-preview-reset-button.history.md](remove-preview-reset-button.history.md)
- Disposition: merged into `main`
- Last verified: `2026-07-18`

## Goal and scope

Remove the Preview-tab "Reset to defaults" control only; leave Display and Energy resets unchanged.

## What landed

- `ClockPreviewScreen` no longer accepts or shows `onReset`; Preview call site no longer resets home/lock settings from that screen.

## Validation recorded

- `python scripts/validate_handoffs.py` passed on the chore branch.
- Grep confirmed Display/Energy reset buttons remain.
- Owner authorized merge of all open branches into `main`.

## Cleanup

- Local branch deleted: yes (cleanup after archive)
- Remote branch deleted: not applicable (never published)
- Worktree `24-hr_clock_widget-wt-remove-preview-reset` removed at cleanup
- Cleanup completed: yes
