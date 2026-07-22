# Energy Model Refinement Playbook

Living guide for re-fitting and validating the personal energy model as more
empirical alertness data accumulates. Read alongside
`docs/handoffs/feature/personal-energy-model-m4.md`.

**Abbreviations:** see [energy_model_glossary.md](energy_model_glossary.md) for
definitions of MAE, LOO-CV, M1–M6, Process S, k_s, outlier classes, and related terms.

**Current baseline (2026-07-22, wake-episode grouping, Fitbit Jul 16–22):** 154 fit
points over 7 days. LOO-CV winner **M3** (MAE 19.3); **M4** LOO MAE 21.1 / R² −0.24,
in-sample MAE **15.5** / R² **0.39**. Production stays on **M4**
(`personal_energy_fit.json`, k_s=0.2) until ≥21 days and a clear CV win. Parameters
are **provisional**.

---

## Data inputs (never commit personal CSV to git)

| Input | Location | Role |
|---|---|---|
| Empirical log | `Alertness_Master_Log.csv` (workspace root, untracked) | Ground-truth subjective energy |
| Fitbit sleep JSON | Loaded via `load_sleep_logs()` in harness | Wake time, duration, debt, naps |
| Prior fit | `analysis/energy_fit_report.json` | M1–M6 CV metrics + `full_fit_params` |
| Production params | `python/personal_energy_fit.json` | Android + `energy_logic.py` runtime |
| Manual log exclusions | `python/energy_log_exclusions.json` | Owner-reviewed outliers removed from fit |

---

## Refinement cadence

| Milestone | LOGGED days | Actions |
|---|---:|---|
| **Weekly** | any | Outlier report + data review + daily plots |
| **Bi-weekly** | ≥10 | Full re-fit (`analyze_energy_fit.py`); compare CV metrics to prior report |
| **Lock-in candidate** | ≥21 | Re-fit; switch to wake-episode grouping if wake MAE ≤ calendar MAE; export to Android |
| **Seasonal** | ≥30 | Re-evaluate M5 (Fourier multi-peak) vs M4; consider day-varying phase (M6+) |

---

## Standard pipeline (run in order)

From repo root with `Alertness_Master_Log.csv` present:

```bash
# 1. Unit tests (fast sanity)
python python/test_energy_fit.py -v

# 2. Full CV re-fit → analysis/energy_fit_report.json
python python/analyze_energy_fit.py

# 3. Classify worst deviations
python python/analyze_energy_fit.py --outlier-report

# 4. Gap policy, sleep restoration, peak/trough predictions
python python/analyze_energy_fit.py --data-review

# 5. Visual comparison (empirical vs legacy vs M4)
python python/analyze_energy_fit.py --daily-plots
```

Review outputs:

| Artifact | What to look for |
|---|---|
| `analysis/energy_fit_report.json` | `cv_winner`, LOO MAE/R² trend vs last run |
| `analysis/m4_outlier_report.md` | `data_suspect` count dropping; `model_limit` clusters shrinking |
| `analysis/data_review_report.md` | SLEEP_RESTORE recovery rising through blocks; POST_WAKE_MODEL reasonable |
| `analysis/daily_comparison/index.html` | Phase alignment; red outlier markers |

---

## Decision tree after each re-fit

### 1. Data quality first (before trusting metrics)

1. Open `m4_outlier_report.md` → manually review top `data_suspect` on daily plots.
2. Correct or exclude confirmed mis-taps in the CSV (do not delete `model_limit` points).
3. `alignment_artifact` counts should stay near zero with wake-episode grouping; investigate if they reappear.
4. Apply **INTERPOLATE** proposals from data review; never treat **MODEL_GUESS** or **SLEEP_RESTORE** as empirical data.

### 2. Choose grouping strategy

Compare in `data_review_report.json`:

- `mae_m4_calendar_grouping` — current production alignment (**17.2** as of 2026-07-22).
- `mae_m4_wake_episode_grouping` — corrects pre-wake misassignment (**16.1**).

**Switch the fitting frame to wake-episode** — default since 2026-07-22 (`build_fitting_frame(..., grouping="wake_episode")`).
Use `--calendar-grouping` on the CLI for legacy comparison runs.

### 3. Choose model tier

| Model | Use when |
|---|---|
| **M4** | Default production tier; interpretable circadian + nightly baseline |
| **M2/M3** | LOO-CV winners on 7-day sample (M2 MAE 21.16 vs M4 23.23); consider if M4 overfits |
| **M5** | LOO MAE beats M4 by ≥1.0 **and** ≥21 days; adds intra-day harmonics (multi-peak) |
| **M6** | Debt dynamics matter after ≥30 days with variable sleep debt |

Fourier coefficients live in `full_fit_params.M5_mixed_fourier.params.fourier_coefs`.
They are already blended lightly in extrema prediction and sleep-aware guesses.

### 4. Sleep dynamics validation

After `--data-review`, spot-check one main-sleep block in the report:

- `recovery_fraction` should **increase** through the block.
- `s_pressure` should **decrease**.
- `projected_wake_energy` should stabilize mid-block (post-wake prediction).

If recovery stays at 0% while `Status == SLEEP_EXCLUDED`, Fitbit timing may not
overlap the log grid — inferred episodes from the CSV should still engage; file a
bug if not.

**k_S tuning (Process S penalty scale):** `predict_energy_sleep_aware()` subtracts
`k_s * s_pressure * 35` from the M4 baseline. Grid-search on Jul 16–22 (2026-07-22)
showed `k_s=1.0` over-penalized post-wake mornings (Jul 21–22 MAE ~18 vs ~10 at
`k_s=0.2`). Production default: **`k_s=0.2`** in `personal_energy_fit.json`.
Re-tune with `python python/tune_k_s.py` after each bi-weekly re-fit.

Naps: when Fitbit logs non-main sleep, `sleep_dynamics.py` discharges S and
applies short inertia without resetting circadian `t` (hours since main wake).

### 5. Export to production

When params are stable (≥21 days, acceptable LOO MAE):

1. Copy winning M4 params from `energy_fit_report.json` → `python/personal_energy_fit.json`.
2. Sync `python/sleep_settings.json` `personal_model` block if present.
3. Mirror in Android `EnergyCalculator.kt` / `ModelSettings.usePersonalModel`.
4. Run `python python/test_energy_fit.py` and Android `EnergyCalculatorTest`.

---

## Gap-fill policy reference

| Action | Confidence | Treat as empirical? |
|---|---|---|
| INTERPOLATE | High | Yes (interpolated) |
| MODEL_GUESS | Medium/low | No — model only |
| SLEEP_RESTORE | Model | No — restoration metadata only |
| POST_WAKE_MODEL | Medium | No — review before use |
| SKIP | N/A | Leave empty |

---

## Metrics to track over time

Log these in the branch handoff history after each meaningful re-fit:

```
n_logged, n_days, cv_winner, loo_mae, loo_r2,
mae_calendar, mae_wake_episode,
data_suspect_count, model_limit_count, alignment_artifact_count
```

**Success signals:** LOO MAE trending down; `model_limit` share falling; daily
plots show M4 tracking empirical peaks within ~2 h; wake-episode MAE crosses
below calendar MAE.

**Stop signals:** LOO MAE rises >2 points vs prior run → check for bad data
batch or overfitting; revert to previous `personal_energy_fit.json`.

---

## Code map (for agents)

| Module | Responsibility |
|---|---|
| `python/analyze_energy_fit.py` | Fit harness, CLI, interpolation, fitting frames |
| `python/energy_models.py` | M1–M6 predictors, extrema detection |
| `python/sleep_dynamics.py` | Process-S timeline, nap/main restoration |
| `python/data_review.py` | Suspicious data, gap audit, peak/trough export |
| `python/energy_logic.py` | Production `get_energy_level()` / personal M4 |

---

## Open work as N grows

- [x] Switch fit harness to wake-episode grouping (default `grouping="wake_episode"`).
- [ ] Wire `sleep_dynamics` into Android `EnergyCalculator` for nap-aware runtime.
- [ ] Adopt M5 or day-varying circadian phase when CV justifies added complexity.
- [ ] Auto-fail CI if LOO MAE regresses >15% vs committed `energy_fit_report.json` baseline.

---

*Last verified: 2026-07-22 — 7 days / 154 fit points; wake-episode grouping; M4 in-sample MAE 15.5.*
