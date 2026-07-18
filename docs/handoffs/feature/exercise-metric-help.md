# Branch: `feature/exercise-metric-help`

## Identity

- Tip: uncommitted — Exercise tab metric glossary help
- Parent/base: `main` at `ead09d4`
- Upstream: none
- Role: feature
- History: [exercise-metric-help.history.md](exercise-metric-help.history.md)
- Last verified: `2026-07-18`

## Goal and scope

- Goal: Define HRSS, TRIMP, and HRV on the Exercise tab at elementary, intermediate, and advanced levels so users can understand readiness jargon.
- Included: App-accurate help copy; glossary chips + 3-level help dialog; chart legend with info taps; fix empty-state tab name (Sleep, not Connect).
- Excluded: Full Exercise redesign; status-copy rewrite; summary tiles; theme-aware chart; baseline slider help.
- Acceptance criteria: Each of HRSS/TRIMP/HRV opens help with three selectable levels; chart legend present; empty state points to Sleep tab.

## Changes since branch creation

- Uncommitted: `MetricHelpContent` with three-level copy; `ExerciseMetricHelp` glossary/dialog/legend; `ExerciseMetricsScreen` wired; empty-state points to Sleep tab.

## Current status

- Working tree: clean on branch tip; implementation WIP is in stash (paused)
- Base relationship: branched from `main` `ead09d4`
- Validation: `python scripts/validate_handoffs.py` passed (2026-07-18) before stash
- Risks/blockers: none known
- Stashes: `stash@{0}` — `wip exercise-metric-help before empirical-log-cutoff` (code + related handoff edits); restore onto this branch only

## Next actions

1. Restore stash onto `feature/exercise-metric-help`, then owner visual check.
2. Commit when requested; then PR into `main`.

## Merge and cleanup

- PR base: `main`
- Readiness: in progress
- Required predecessor: none
- After merge: append closure history, archive this file and its history, update global HISTORY/index, then delete local/remote branch after reachability verification.
