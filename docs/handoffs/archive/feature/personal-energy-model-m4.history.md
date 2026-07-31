# Archived history: `feature/personal-energy-model-m4`

Append-only milestones. Current state: [personal-energy-model-m4.md](personal-energy-model-m4.md).

### 2026-07-22 â€” Branch created
- Parent: `main` at `02fa6f4`
- Change: Moved uncommitted M4 energy retrofit WIP off `main` onto `feature/personal-energy-model-m4`.
- Validation: `test_energy_fit.py` and `validate_handoffs.py` passed on dirty tree.

### 2026-07-22 â€” Rebased onto fold-calendar main
- Parent: `main` at `43f0872` (fold-calendar merged)
- Change: Recreated branch from updated `main`; restored energy WIP via stash; `MainActivity.kt` auto-merged with fold-calendar Display changes.
- Validation: handoff docs updated; validator pending.

### 2026-07-22 â€” Sleep dynamics model
- Change: `sleep_dynamics.py` models Process-S discharge during sleep; data review uses SLEEP_RESTORE and POST_WAKE_MODEL instead of SKIP.
- Validation: 16 tests pass; runtime logs confirmed decreasing s_pressure during sleep blocks.
- Docs: `docs/energy_model_refinement.md` playbook; debug instrumentation removed after owner proceed.

### 2026-07-22 â€” Empirical sleep-input fallback (Fitbit gap fix)
- Change: `empirical_sleep.py` infers wake/duration from SLEEP_EXCLUDED blocks; fit harness honors `excluded_dates`; sleep dynamics merges log-inferred episodes when `raw_df` passed.
- Validation: 18 tests pass; Jul 20 wake_hour 10.5 (was 0.0); sleep-aware MAE 36â†’22 with `raw_df`; overlap gap surfaced in fit report.

### 2026-07-22 â€” Owner outlier review + sleep-gap model updates
- Change: `energy_log_exclusions.json` (4 points excluded); outlier classifier adds `wake_variation` for sleep gaps and post-wake windows; M4 evaluation uses `predict_energy_sleep_aware` in plots/residuals.
- Metrics: 154 fit points (was 158); data_suspect 8 (was 18); wake_variation 10; sleep-dynamics MAE shifts on daily plots.
- Owner rulings: Jul 17 #3,4,5 and Jul 18 #10 excluded; all other reviewed suspects kept.
- k_s tuned to **0.2** (was 1.0); `_load_m4_params` now merges personal overrides; Jul 21 MAE 22.2â†’14.5, Jul 22 13.8â†’5.5 on daily plots.

- Docs: `docs/energy_model_glossary.md` â€” abbreviations defined for reports and handoffs.

### 2026-07-22 â€” Wake-episode grouping default
- Change: `build_fitting_frame(..., grouping="wake_episode")` default; helpers moved to `empirical_sleep.py`; `--calendar-grouping` CLI flag.
- Metrics: M4 in-sample MAE 15.5 / RÂ² 0.39; alignment_artifact 0; Jul 21 M4 MAE 11.0.

### 2026-07-22 â€” Docs glossary + branch commit `aa398ca`
- Change: `docs/energy_model_glossary.md` defines MAE, LOO-CV, M1â€“M6, Process S, k_s, outlier classes; linked from playbook, handoff, validation report.
- Validation: 26 tests pass; handoff validator pass; commit excludes empirical CSV.
- Validation: 26 tests pass; full pipeline + validation report refreshed.

### 2026-07-22 — Energy % display denominator fix
- Change: Python `clock_widget.py` and Android `ClockRenderer.kt` now divide by today's curve peak (same sleep/debt inputs), not the perfection reference (goal sleep, zero debt). Added `TestEnergyDisplayPct`.; ``docs/energy_model_glossary.md`` — **UI display** section (Energy %, Acrophase, Perfection reference).
- Cause: M4 negative `baseline_sleep_coef` raises today's curve above the perfection baseline when actual sleep < `bedtime_goal`, yielding >100% labels (e.g. 102% at 4h sleep in verification logs).

### 2026-07-26 — Python Display UI alignment: Bathyphase, Acrophase, Grogginess, and Wind-down Wedge
- Change: Exposed Bathyphase Indicator and Acrophase Indicator checkboxes in Python control panel; added `show_grogginess` and `show_wind_down` BooleanVars with UI toggles under SLEEP section, JSON persistence in `sleep_settings.json`, and pre-bedtime 90-minute translucent wind-down arc rendering in `draw_sleep_arc()`.
- Validation: `python -m compileall python`, `python scripts/validate_handoffs.py` passed.

### 2026-07-26 — Python Display UI alignment: Bedtime Countdown standalone window
- Change: Added standalone draggable `bedtime_countdown_window` Toplevel with `_build_bedtime_countdown()`, `_resolve_bedtime_target()` (mirrors Android's `BedtimeNotificationManager.resolveBedtimeMillis`: fall-asleep − 90 min, rounded to 5 min, 10 PM clamp for 22:00–23:30), `update_bedtime_countdown_visibility()`, and `_update_bedtime_countdown_tick()` (1-second live ticker). Defaulted to ON (`show_bedtime_countdown = True`). Unified `check_nearby()` time-window tracking (`in_time_window`), using screen mouse pointer coordinates (`winfo_pointerx/y`) across transparent background regions to trigger `hover_delay_var` solid transition (`make_solid()`) and `timeout_delay_var` transparent transition (`make_clock_transparent()`).
- Validation: `python -m compileall python`, 27 tests passed, `python scripts/validate_handoffs.py` passed.

### 2026-07-31 — Merged and archived
- Merge: `62cb343` merged the feature history into `main`; `aec4184` merged subsequent adaptive refinements.
- Cleanup: verified the feature tip is reachable from `main`, deleted the local feature branch, and archived this handoff.
