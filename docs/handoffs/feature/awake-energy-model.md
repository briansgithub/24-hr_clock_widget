# Branch: `feature/awake-energy-model`

## Identity

- Base: `main` at `62b8d19` — `Scale sun and moon icons to 1.25-hour diameter`
- Tip: validated awake-energy checkpoint on this branch
- Upstream: none
- Role: feature
- Worktree: primary checkout at `H:\Desktop\widgets\24-hr_clock_widget`
- Owned paths: energy model, fit tooling, clock renderers, focused tests, and this branch's handoff records
- History: [awake-energy-model.history.md](awake-energy-model.history.md)
- Last verified: `2026-08-24`

## Goal and scope

- Goal: Make subjective energy an awake-only prediction on Python and Android while developing a validation-gated M9 hybrid candidate.
- Included: sleep-aware prediction states, awake-only rendering/normalization/extrema, reproducible candidate fitting and export checks, cross-platform tests.
- Excluded: projected future sleep, stage-level awake gaps, meal/caffeine/exercise inputs, and production M9 promotion before the 21-episode gate.
- Acceptance criteria: sleep produces no energy value or connecting curve; M8 awake values remain compatible; candidate promotion is gated; Python and Android checks pass.

## Changes since branch creation

- Awake-only implementation across Python and Android:
  - Added matching sleep-interval/context and `AWAKE` / `ASLEEP` / `UNAVAILABLE` prediction contracts.
  - Masked main sleep and every recorded nap independently of the sleep-arc and nap-effects toggles.
  - Split curve rendering into exact awake segments; percentages, normalization, and acrophase now ignore sleep/unavailable values.
  - Propagated the behavior to the Python live widget/snapshots and Android preview/wallpaper.
- Added the canonical provisional M9 fitter (`python/fit_awake_hybrid_model.py`), deterministic Android exporter/drift check (`python/export_energy_model.py`), generated Android constants, focused Python/Android tests, and model documentation.
- Generated a provisional validation report at `analysis/m9_awake_hybrid_validation.md`; ignored JSON candidate/report files are reproducible by rerunning the fitter.
- Local Android inspection screenshots are currently untracked under `analysis/`. They contain device UI details; do not commit or delete them without owner direction.

## Current status

- Production remains on committed `M8_adaptive_sleep`; its awake numeric predictions were deliberately not replaced.
- Canonical M9 result: 17 qualified wake episodes / 480 original ratings. M8 MAE/RMSE was 13.90/16.94; provisional M9 was 15.10/18.36, won 23.5% of episodes, reproduced the held-out peak–dip–peak shape on 25.0%, and failed every promotion gate.
- Final local-only acceptance checks:
  - `python -m compileall python` — PASS.
  - `python python/test_adaptive_energy_model.py` — PASS.
  - `python python/test_energy_fit.py` — PASS.
  - `python python/test_debt_logic.py` — PASS.
  - `.\gradlew.bat :app:testDebugUnitTest` — PASS.
  - `python python/export_energy_model.py --check` — PASS.
- Additional completed checks:
  - `python python/fit_awake_hybrid_model.py` — PASS (full candidate/report run).
  - `.\gradlew.bat :app:installDebug` — PASS after the Android source edits; this also compiled the installed debug build.
  - `python scripts/validate_handoffs.py --quiet` — PASS after this handoff update.
- Visual inspection:
  - Android preview showed an awake-only curve gap with the sleep arc visible and again with the arc hidden; normalization was temporarily enabled and then restored off.
  - Python visual inspection stopped at the Fitbit authentication flow; no login was automated.
- Owner restriction: **do not execute commands, install builds, launch the app, or otherwise interact with the Pixel 7a unless the owner gives fresh explicit permission.**
- The owner authorized a local branch commit on 2026-08-24. Push, PR, and merge remain unauthorized.

## Next actions

1. Keep M8 production parameters. Do not promote M9 unless a future dataset reaches all stated sample, accuracy, episode-win, and shape gates.
2. Do not use the Pixel 7a until the owner gives fresh explicit permission.
3. Ask the owner whether to retain or remove the three untracked Android inspection screenshots; do not commit them because they contain device UI details.
4. Obtain explicit authorization before push, PR, or merge.

## Merge and cleanup

- PR base: `main`
- Readiness: validated local checkpoint ready for owner review; M9 is not eligible for promotion, and push/PR/merge are not authorized
- Required predecessor: none
- Primary checkout: `H:\Desktop\widgets\24-hr_clock_widget`
- After merge: verify reachability from the remote default branch; append closure history, archive this file and its history, update global HISTORY/index, then delete branches only after authorization.
