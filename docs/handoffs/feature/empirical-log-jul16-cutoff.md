# Branch: `feature/empirical-log-jul16-cutoff`

## Identity

- Tip: pending commit — Jul 16 2026 cutoff + Drive URL autofill
- Parent/base: `main` at `ead09d4`
- Upstream: none
- Role: feature
- History: [empirical-log-jul16-cutoff.history.md](empirical-log-jul16-cutoff.history.md)
- Last verified: `2026-07-18`

## Goal and scope

- Goal: Keep empirical energy logs from 2026-07-16 onward only, and make the 10:00 PM missed-data alert count that calendar day only.
- Included: Cutoff prune on load/save/import/merge/export; history window clamped to cutoff; today-only missed count; Drive URL autofill with external label.
- Excluded: Survey cadence changes; history UI redesign.
- Acceptance criteria: No pre-2026-07-16 rows after save/sync; 10 PM alert is today-only; Drive URL field shows deployment URL with external label.

## Changes since branch creation

- Cutoff prune + today-only missed alert + Drive URL autofill/label UX (this commit).

## Current status

- Working tree: committing for merge into `main` (owner requested commit + merge).
- Base relationship: branched from `main` `ead09d4`.
- Validation: handoff validator; Android compile deferred (no JAVA_HOME).
- Risks/blockers: none blocking merge; public CSV drops pre-cutoff on next sync.
- Stashes: unrelated paused feature stashes — leave untouched.

## Next actions

1. Commit and merge into `main` (`--no-ff`).
2. Archive handoff/history; update HISTORY/README/main; delete local branch.

## Merge and cleanup

- PR base: `main` (local merge; no remote feature branch)
- Readiness: merging now per owner
- Required predecessor: none
- After merge: archive, HISTORY, index, delete local branch.
