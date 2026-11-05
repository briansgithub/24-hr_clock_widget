# History: `feature/energy-refit-aug09`

Append-only milestones for this branch. Current state and next actions belong in [`energy-refit-aug09.md`](energy-refit-aug09.md).

### 2026-08-09 — Branch created

- Commit/base: `main` at `5405993`, in sibling worktree `..-wt-energy-refit`.
- Change: none yet.
- Validation/decision: worktree chosen over stashing so the primary checkout's `feature/exercise-redesign` WIP stays untouched.

### 2026-08-09 — Recovered the sleep inputs the fit depends on

- Change: added `python/refresh_fitbit_sleep_cache.py` (explicit date range, backup, merge by `logId`) and `python/repair_alertness_log.py`. Rebuilt `Alertness_Master_Log.csv` from the Aug 9 export with `SLEEP_EXCLUDED` re-derived from Fitbit intervals.
- Validation/decision: the Aug 9 export had lost every sleep marker before Jul 26 — `EmpiricalEnergyManager.runRetroactiveSleepFilter` reverts them outside the app's rolling 14-day window, so each export destroys older sleep history. The cached Fitbit logs covered only Jul 22–Aug 5, and `wake_context_for_datetime` silently reuses the nearest episode, which fabricated wake times on 10 of 24 days and a 55 h phantom sleep debt. After re-authentication a full Jul 16–Aug 9 fetch made Fitbit authoritative for all 25 nights. `get_all_energy_inputs` was not used: it fetches only `[today-14, today]` and rewrites the cache wholesale.

### 2026-08-09 — Re-fit M8 on 18 days

- Change: added `python/fit_adaptive_sleep_model.py`, the first committed fitter able to produce M8; refreshed `personal_energy_fit.json`; mirrored `circadian_coefs` into `energy_logic.py` and `EnergyCalculator.kt`; regenerated the four cross-language golden vectors.
- Metrics: n_logged 476 scored over n_days 18 (Jul 16–26, Jul 31, Aug 1–3, Aug 5, Aug 7–8); LOO MAE 15.058, RMSE 18.150; in-sample MAE 14.290; correction-free baseline LOO MAE 15.322; best grid candidate 14.839; LOO MAE standard error 0.475. Previous production record: 11 days / 302 points / cv_mae 16.098. Excluded below `min_logged_per_day=20`: Jul 27–30, Aug 4, Aug 6, Aug 9.
- Validation/decision: only the five Fourier baseline coefficients were refit. 188,873 of 194,481 correction candidates (97%) fall within one standard error of the best, so the block is not identifiable from this sample — free minimisation pinned five of six parameters to grid bounds, and a one-standard-error rule collapsed to the zero-correction corner, which would have reduced M8 to the M7 clock curve and deleted its response to sleep debt. The block is therefore held at its production values, which score 15.058 against 15.322 for no corrections.
- Validation: `compileall` clean; `test_adaptive_energy_model.py` 8/8; `test_energy_fit.py` 27/27; `test_debt_logic.py` pass; parameter copies and golden vectors verified identical across JSON, Python, and Kotlin. Android tests **not run** — no Gradle wrapper, no root build script, no `gradle` on PATH.

### 2026-08-09 — `reference_peak_clock` corrected to track the fitted curve

- Change: `reference_peak_clock` is now derived from the solved coefficients rather than held at a literal; 20.0 -> 13.48. Mirrored into `energy_logic.py` and `EnergyCalculator.kt`, and the two phase-shifted golden vectors regenerated.
- Validation/decision: the parameter names the clock hour where the baseline peaks and is the reference the bathyphase phase shift is measured against. The previous value matched the previous fit (old baseline peaked at 19.97 h) but the refit peaks at 13.48 h, so leaving it would have biased every bathyphase-driven shift by ~6.5 h — up to the full +/-3 h clamp. Treating it as fitted output rather than configuration prevents the two from drifting apart again.
- Note: the peak sits on a broad plateau (roughly 64-66 pts from 12:00 to 20:00), so its exact location is weakly determined even though the anchor must match it.

### 2026-08-09 — Removed legacy fitters; untracked the empirical CSVs

- Change: deleted `python/optimize_energy_model.py` and `python/tune_k_s.py`; rewrote the k_S section of `docs/energy_model_refinement.md` as historical. Added `Alertness_Master_Log*.csv` to `.gitignore` and removed `Alertness_Master_Log.csv` and `(2).csv` from the index (both remain on disk).
- Validation/decision: `optimize_energy_model.py` overwrote the production `personal_energy_fit.json` with an M1-M6 winner and was referenced only by a frozen archived handoff. `tune_k_s.py` tuned `k_s`, which is not an M8 parameter, and its objective was hardcoded to 2026-07-21/22 while scoring missing dates as zero error, so it rewarded absent data. Tracking the CSVs contradicted the "never commit personal CSV" policy at `docs/energy_model_refinement.md:18`.
- Consequence: a fresh clone has no training data and must be given a local export before `fit_adaptive_sleep_model.py` will run. Git history still contains the previously committed copies; purging those requires a history rewrite and force-push and has not been done.
