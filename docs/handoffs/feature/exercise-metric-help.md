# Branch: `feature/exercise-metric-help`

## Identity

- Tip: `e041101` — merge of `origin/main` onto feature (product `c24547e`)
- Parent/base: `main` at `cd755b3` (0 behind)
- Upstream: `origin/feature/exercise-metric-help` (push pending)
- Role: feature
- History: [exercise-metric-help.history.md](exercise-metric-help.history.md)
- Last verified: `2026-07-18`

## Goal and scope

- Goal: Define HRSS, TRIMP, and HRV on the Exercise tab at elementary, intermediate, and advanced levels so users can understand readiness jargon.
- Included: App-accurate help copy; glossary chips + 3-level help dialog; chart legend with info taps; fix empty-state tab name (Sleep, not Connect).
- Excluded: Full Exercise redesign; status-copy rewrite; summary tiles; theme-aware chart; baseline slider help.
- Acceptance criteria: Each of HRSS/TRIMP/HRV opens help with three selectable levels; chart legend present; empty state points to Sleep tab.

## Changes since branch creation

- `c24547e`: `MetricHelpContent.kt`, `ExerciseMetricHelp.kt`, `MainActivity` ExerciseMetricsScreen wiring.
- `e041101`: merged current `main` (energy + display already on main).

## Current status

- Working tree: clean product; handoff refresh in this commit.
- Owner visual check: PASSED (authorized commit/push/PR).
- Stashes: `stash@{1}` still holds original exercise WIP (product restored); drop when owner confirms. `stash@{0}` is unrelated main inventory stash.

## Next actions

1. Push + open PR into `main`.
2. After merge: archive handoff/history, update HISTORY/index, delete local/remote branch.
3. Owner: authorize `git stash drop` for exercise stash when satisfied.

## Merge and cleanup

- PR base: `main`
- Readiness: ready for push/PR
- Required predecessor: none
- After merge to main: append closure history, archive this file and its history, update global HISTORY/index, then delete local/remote branch after reachability verification.
