# Archived branch: `feature/energy-refit-aug09`

## Identity

- Final tip: `edd8eb1` — Remove legacy fitters; stop tracking the empirical alertness CSVs
- Merge commit / PR: `477d4c2` on local `main` (not pushed)
- History: [energy-refit-aug09.history.md](energy-refit-aug09.history.md)
- Closed: `2026-11-05`

## Goal and scope

Re-fit the M8 circadian baseline on the alertness log through 2026-08-09 and keep that fit reproducible.

## What landed

- Aug 9 circadian coefficients and `reference_peak_clock` 13.48 in `personal_energy_fit.json`, Python defaults, and `GeneratedEnergyModel.kt`.
- Awake-only prediction code on `main` stayed in place; Kotlin still reads the generated constants.
- Fitter `python/fit_adaptive_sleep_model.py` plus sleep-cache and CSV repair helpers. Legacy `optimize_energy_model.py` and `tune_k_s.py` removed.

## Validation

- At merge: `python python/export_energy_model.py --check` and `python -m unittest python.test_adaptive_energy_model` (15 tests) passed.
- The original branch did not run Android tests. The golden vectors were updated with the coefficients and are covered by `EnergyCalculatorTest` on later Android runs.

## Disposition

- Status: merged into `main`
- Remote branch deleted: not applicable (never pushed)
- Local branch deleted: yes
- Cleanup completed: yes; sibling worktree removed after the merge was reachable from local `main`
