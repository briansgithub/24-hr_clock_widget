# Model Fit Validation Report

Generated: 2026-07-22T19:05:13
Empirical log: H:\Desktop\widgets\24-hr_clock_widget\Alertness_Master_Log.csv
Analysis report: 2026-07-22T18:58:41

## Executive summary

- **Sample:** 154 LOGGED points over 7 days (2026-07-16 to 2026-07-22)
- **Overlap:** 7 days with both empirical and Fitbit sleep
- **Sleep gaps:** none
- **Excluded dates:** none
- **Sleep grouping:** wake_episode
- **Production model:** M4_circadian_daily_baseline (`personal_energy_fit.json`)
- **LOO CV winner:** M3_scaled_process_s (M4 kept for interpretability)

Abbreviations (MAE, LOO, R², M1–M6, Process S, k_s, outlier classes): [energy_model_glossary.md](../docs/energy_model_glossary.md).

## M4 accuracy

| Metric | In-sample | Leave-one-day-out |
|---|---:|---:|
| MAE | 15.5 | 21.1 |
| RMSE | 17.9 | 25.6 |
| R² | 0.393 | -0.241 |
| Bias | -0.00 | 1.42 |

### Grouping comparison

- **Calendar-date MAE:** 15.4
- **Wake-episode MAE:** 15.4

Fit harness defaults to **wake-episode** grouping; calendar-date MAE is kept for regression comparison. Wake-episode grouping fixes pre-wake midnight logs that previously used the wrong night's sleep inputs.

## Model comparison (LOO CV)

| Rank | Model | LOO MAE | LOO R² |
|---:|---|---:|---:|
| 1 | M3_scaled_process_s (CV winner) | 19.3 | -0.008 |
| 2 | M2_tuned_two_process | 20.0 | -0.076 |
| 3 | M5_mixed_fourier | 20.4 | -0.047 |
| 4 | M6_time_varying_debt | 20.9 | -0.162 |
| 5 | **M4_circadian_daily_baseline** | 21.1 | -0.241 |
| 6 | M1_baseline | 25.4 | -0.827 |

Production stays on **M4_circadian_daily_baseline** unless LOO improves by ≥2 MAE points with acceptable interpretability.

## Per-day breakdown

| Date | n | Sleep source | M4 MAE | Legacy 2-proc MAE | Notes |
|---|---:|---|---:|---:|---|
| 2026-07-16 | 26 | fitbit | 16.6 | 49.2 |  |
| 2026-07-17 | 24 | fitbit | 11.9 | 33.3 | Strong day-level fit |
| 2026-07-18 | 19 | fitbit | 16.8 | 67.3 |  |
| 2026-07-19 | 20 | fitbit | 17.5 | 47.5 |  |
| 2026-07-20 | 28 | fitbit | 21.9 | 29.2 | Worst day; midnight pre-wake LOGGED cluster (alignment artifacts) |
| 2026-07-21 | 27 | fitbit | 12.6 | 31.5 | Strong day-level fit |
| 2026-07-22 | 10 | fitbit | 5.8 | 30.3 | Best fit in sample |

## Outlier summary

Points with |error| ≥ 12: **89** of 154

| Class | Count | Meaning |
|---|---:|---|
| alignment_artifact | 0 | Pre-wake logs using wrong night's Fitbit row |
| wake_variation | 10 | Expected post-sleep / wake-boundary discontinuity |
| model_limit | 62 | Systematic local bias / fixed circadian phase |
| data_suspect | 8 | Isolated spikes or post-gap inconsistencies |
| ambiguous | 9 | Could not classify confidently |

Of 89 points with |error| ≥ 12, 0 look like **wake-alignment artifacts** (pre-wake logs using wrong night's Fitbit row), 10 are **expected wake/sleep-gap variation**, 8 look **data-suspect** (isolated spikes or post-gap inconsistencies), 62 reflect **model limits** (systematic local bias), and 9 are **ambiguous**. Model-limit errors cluster where M4's fixed circadian phase cannot match your day-specific peak timing; expect this until phase can vary by day or more data is logged. Data-suspect points should be manually reviewed in the log; exclude only if you confirm a mis-tap or inconsistent scale use. Largest miss: 2026-07-20 17:00:00 observed 25 vs M4 63.1 (model_limit) — Local systematic bias (phase_mismatch); 4 neighbors share residual sign.

## Graphs

### Per-day comparison (Jul 16–22)

- [2026-07-16](daily_comparison/2026-07-16.png) — M4 MAE 16.6
- [2026-07-17](daily_comparison/2026-07-17.png) — M4 MAE 11.9
- [2026-07-18](daily_comparison/2026-07-18.png) — M4 MAE 16.8
- [2026-07-19](daily_comparison/2026-07-19.png) — M4 MAE 17.5
- [2026-07-20](daily_comparison/2026-07-20.png) — M4 MAE 21.9
- [2026-07-21](daily_comparison/2026-07-21.png) — M4 MAE 12.6
- [2026-07-22](daily_comparison/2026-07-22.png) — M4 MAE 5.8

- [Gallery (all days)](daily_comparison/index.html)
- [Aggregate fit plots](energy_fit_plots.png)
- [M4 residual diagnostics](m4_residual_diagnostics.png)

## Interpretation

M4 in-sample MAE ~16 on 154 points (wake-episode grouping). LOO MAE ~21 and LOO R² -0.24 are expected to be weak on a 7-day sample — the model is not yet validated for day-ahead prediction.

**Strengths:** Jul 21–22 show the tightest per-day fits. Wake-episode grouping eliminated alignment-artifact outliers from the pre-wake midnight cluster.

**Limitations:** Jul 20 evening still shows the largest model_limit cluster. Fixed circadian phase cannot track day-specific peak timing.

**Next steps:**
1. Continue daily logging through Jul 25 (day-10 bi-weekly re-fit gate).
2. Manually review data_suspect outliers before excluding any ratings.
3. Re-fit M4 after Jul 25 when ≥10 overlap days are available.
