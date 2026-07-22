# History: `feature/personal-energy-model-m4`

Append-only milestones. Current state: [personal-energy-model-m4.md](personal-energy-model-m4.md).

### 2026-07-22 — Branch created
- Parent: `main` at `02fa6f4`
- Change: Moved uncommitted M4 energy retrofit WIP off `main` onto `feature/personal-energy-model-m4`.
- Validation: `test_energy_fit.py` and `validate_handoffs.py` passed on dirty tree.

### 2026-07-22 — Rebased onto fold-calendar main
- Parent: `main` at `43f0872` (fold-calendar merged)
- Change: Recreated branch from updated `main`; restored energy WIP via stash; `MainActivity.kt` auto-merged with fold-calendar Display changes.
- Validation: handoff docs updated; validator pending.

### 2026-07-22 — Sleep dynamics model
- Change: `sleep_dynamics.py` models Process-S discharge during sleep; data review uses SLEEP_RESTORE and POST_WAKE_MODEL instead of SKIP.
- Validation: 16 tests pass; runtime logs confirmed decreasing s_pressure during sleep blocks.
- Docs: `docs/energy_model_refinement.md` playbook; debug instrumentation removed after owner proceed.

### 2026-07-22 — Empirical sleep-input fallback (Fitbit gap fix)
- Change: `empirical_sleep.py` infers wake/duration from SLEEP_EXCLUDED blocks; fit harness honors `excluded_dates`; sleep dynamics merges log-inferred episodes when `raw_df` passed.
- Validation: 18 tests pass; Jul 20 wake_hour 10.5 (was 0.0); sleep-aware MAE 36→22 with `raw_df`; overlap gap surfaced in fit report.

### 2026-07-22 — Owner outlier review + sleep-gap model updates
- Change: `energy_log_exclusions.json` (4 points excluded); outlier classifier adds `wake_variation` for sleep gaps and post-wake windows; M4 evaluation uses `predict_energy_sleep_aware` in plots/residuals.
- Metrics: 154 fit points (was 158); data_suspect 8 (was 18); wake_variation 10; sleep-dynamics MAE shifts on daily plots.
- Owner rulings: Jul 17 #3,4,5 and Jul 18 #10 excluded; all other reviewed suspects kept.
- k_s tuned to **0.2** (was 1.0); `_load_m4_params` now merges personal overrides; Jul 21 MAE 22.2→14.5, Jul 22 13.8→5.5 on daily plots.

- Docs: `docs/energy_model_glossary.md` — abbreviations defined for reports and handoffs.

### 2026-07-22 — Wake-episode grouping default
- Change: `build_fitting_frame(..., grouping="wake_episode")` default; helpers moved to `empirical_sleep.py`; `--calendar-grouping` CLI flag.
- Metrics: M4 in-sample MAE 15.5 / R² 0.39; alignment_artifact 0; Jul 21 M4 MAE 11.0.
- Validation: 26 tests pass; full pipeline + validation report refreshed.
