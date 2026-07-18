# Archived: `feature/exercise-metric-help`

## Identity

- Final tip: `d1fac1a` — fix: keep Exercise metric help only on the chart legend
- Feature tip: `c24547e` — feat: add three-level Exercise metric help for HRSS/TRIMP/HRV
- Merge / PR: `ed22464` — [PR #3](https://github.com/briansgithub/24-hr_clock_widget/pull/3)
- Parent/base: `main` at `cd755b3`
- History: [exercise-metric-help.history.md](exercise-metric-help.history.md)
- Disposition: merged into `main`
- Last verified: `2026-07-18`

## Goal and scope

- Three-level HRSS / TRIMP / HRV help on the Exercise tab.
- Help entry only via chart legend below the graph (no separate glossary row).
- Empty-state wording points to Sleep tab.

## What landed

- `MetricHelpContent.kt`, `ExerciseMetricHelp.kt` (dialog + chart legend).
- `MainActivity` ExerciseMetricsScreen wiring; glossary row removed before merge.

## Validation recorded

- Owner visual check PASSED; legend-only help change approved.
- Exercise WIP stash dropped after product verified on `main`.

## Cleanup

- Local branch deleted: yes
- Remote branch deleted: yes (with PR merge)
- Exercise worktree removed: yes
- Exercise stash dropped: yes
