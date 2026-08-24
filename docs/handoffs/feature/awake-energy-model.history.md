# History: `feature/awake-energy-model`

Append-only milestones for this branch. Current state and next actions belong in [awake-energy-model.md](awake-energy-model.md).

### 2026-08-24 — Branch created
- Commit/base: `main` at `62b8d19`
- Change: Created `feature/awake-energy-model` for awake-only energy prediction, rendering, and a gated M9 candidate workflow.
- Validation/decision: Owner selected a hybrid model, Python/Android parity, blank sleep gaps, and promotion only after 21 qualified wake episodes plus the established accuracy gate.

### 2026-08-24 — Awake-only implementation handed off
- Commit/base: uncommitted working tree on `feature/awake-energy-model`, based at `62b8d19`.
- Change: Implemented cross-platform sleep prediction states, exact awake curve segments, awake-only extrema/percentages, provisional M9 fitting/validation, deterministic Android export, compatibility fallback, tests, and documentation.
- Validation/decision: The current 17-episode/480-rating candidate regressed versus M8 (MAE 15.10 vs 13.90; RMSE 18.36 vs 16.94) and failed every promotion gate, so production remains M8. Local focused checks passed during implementation, but the final suite must be rerun after the last small edits.
- Restriction: Do not execute on or interact with the Pixel 7a without fresh explicit owner permission. Commit/push/PR remain unauthorized.

### 2026-08-24 — Final local validation and checkpoint
- Commit/base: validated checkpoint on `feature/awake-energy-model`, based at `62b8d19`.
- Change: Recorded the completed awake-only implementation and retained M8 after the provisional M9 candidate failed the promotion gates.
- Validation/decision: Python compilation, adaptive-model tests, fitter tests, debt tests, Android unit tests, and deterministic export drift check all passed locally. The owner authorized a local commit; push, PR, and merge remain unauthorized.
- Restriction: No commands or interaction may target the Pixel 7a without fresh explicit owner permission. Three local Android inspection screenshots remain deliberately untracked.
