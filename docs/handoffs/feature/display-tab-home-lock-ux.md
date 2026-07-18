# Branch: `feature/display-tab-home-lock-ux`

## Identity

- Tip: uncommitted — Display tab Home/Lock UX cleanup
- Parent/base: `main` at `ead09d4`
- Upstream: none
- Role: feature
- History: [display-tab-home-lock-ux.history.md](display-tab-home-lock-ux.history.md)
- Last verified: `2026-07-18`

## Goal and scope

- Goal: Make Display Home vs Lock tabs clearer and remove redundant preview entry.
- Included: Remove Display-tab Preview button/dialog; show Reset Home only on Home tab and Reset Lock only on Lock tab; add Home/Lock tab icons.
- Excluded: Preview tab behavior; wallpaper set flow; element toggle list.
- Acceptance criteria: Display has no Preview button; each tab shows only its own “Reset to Defaults”; Home/Lock tabs show distinct icons.

## Changes since branch creation

- Uncommitted: `DisplaySettingsScreen` drops inline preview dialog; single tab-scoped “Reset to Defaults” button; Material Home/Lock icons on tabs; unused preview-only params removed from call site.

## Current status

- Working tree: Android WIP is in stash (`wip display-tab-home-lock-ux before exercise-metric-help`); restore with `git checkout feature/display-tab-home-lock-ux` then `git stash pop` when resuming.
- Base relationship: branched from `main` `ead09d4`
- Validation: not run (Android toolchain may be unavailable)
- Risks/blockers: none known
- Stashes: display-tab code paused while `feature/exercise-metric-help` is active

## Next actions

1. Restore stash and finish owner visual check on Display Home/Lock tabs.
2. Commit when requested; then PR into `main`.

## Merge and cleanup

- PR base: `main`
- Readiness: in progress
- Required predecessor: none
- After merge: append closure history, archive this file and its history, update global HISTORY/index, then delete local/remote branch after reachability verification.
