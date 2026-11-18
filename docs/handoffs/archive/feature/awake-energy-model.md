# Archived branch: `feature/awake-energy-model`

## Identity

- Final tip: `393c533` — Fix reauth tooltip sticking open after clock redraws
- Parent/base: `main` at `62b8d19` — Scale sun and moon icons to 1.25-hour diameter
- Merge commit / PR: fast-forward on `main` through `5c6e028` / `393c533` (local; not pushed)
- Upstream: none
- Role: feature — awake-only energy prediction and gated M9 candidate
- History: [awake-energy-model.history.md](awake-energy-model.history.md)
- Closed: `2026-09-15`

## Goal and scope

- Goal: Make subjective energy an awake-only prediction on Python and Android while developing a validation-gated M9 hybrid candidate.
- Included: sleep-aware prediction states, awake-only rendering/normalization/extrema, reproducible candidate fitting and export checks, cross-platform tests.
- Excluded: projected future sleep, stage-level awake gaps, meal/caffeine/exercise inputs, and production M9 promotion before the 21-episode gate.

## What landed

- Matching sleep-interval/context and `AWAKE` / `ASLEEP` / `UNAVAILABLE` prediction contracts on Python and Android.
- Main sleep and recorded naps masked independently of sleep-arc / nap-effects toggles; awake-only curve segments, percentages, normalization, and acrophase.
- Provisional M9 fitter (`python/fit_awake_hybrid_model.py`), Android export/drift check (`python/export_energy_model.py`), generated constants, tests, and model docs.
- Production retained committed `M8_adaptive_sleep`; provisional M9 failed every promotion gate (17 qualified episodes / 480 ratings).

## Validation

- Final local suite recorded on 2026-08-24: `compileall`, adaptive/fitter/debt Python tests, `:app:testDebugUnitTest`, and `export_energy_model.py --check` all passed.
- Android preview inspection confirmed awake-only curve gaps; Pixel 7a interaction remained owner-gated.
- Later merge also brought the Python reauth tooltip redraw fix (`393c533`).

## Disposition

- Status: merged into `main` (fast-forward)
- Remote branch deleted: not applicable (never pushed)
- Local branch deleted: yes
- Cleanup completed: yes; `feature/energy-refit-aug09` worktree at `H:/Desktop/widgets/24-hr_clock_widget-wt-energy-refit` intentionally preserved (unmerged unique commits)
