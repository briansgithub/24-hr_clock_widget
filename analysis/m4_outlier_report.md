# M4 Outlier Deviation Report

Generated: 2026-07-29T16:28:08
Points analyzed: 314 LOGGED over 12 days
Outlier threshold: |error| ≥ 12.0

## Classification summary

- **model_limit**: 134
- **wake_variation**: 24
- **ambiguous**: 20
- **data_suspect**: 15

## Top outliers

| DateTime | Obs | Pred | Error | Class | Rationale |
|---|---:|---:|---:|---|---|
| 2026-07-20 02:00:00 | 80 | 26.7 | +53.3 | model_limit | Local systematic bias (phase_mismatch); 3 neighbors share residual sign. |
| 2026-07-25 23:30:00 | 78 | 24.9 | +53.1 | ambiguous | Large error without a confident dirty-data or model signal. |
| 2026-07-20 02:30:00 | 77 | 24.0 | +53.0 | model_limit | Local systematic bias (phase_mismatch); 3 neighbors share residual sign. |
| 2026-07-22 22:00:00 | 90 | 38.1 | +51.9 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-22 21:30:00 | 89 | 38.0 | +51.0 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-20 01:30:00 | 80 | 29.4 | +50.6 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-22 20:30:00 | 83 | 33.5 | +49.5 | wake_variation | Expected post-sleep or wake-boundary variation (gap was sleep or t<2.5h since wake). |
| 2026-07-25 23:00:00 | 77 | 27.6 | +49.4 | model_limit | Local systematic bias (phase_mismatch); 3 neighbors share residual sign. |
| 2026-07-22 21:00:00 | 85 | 36.6 | +48.4 | model_limit | Local systematic bias (phase_mismatch); 3 neighbors share residual sign. |
| 2026-07-20 01:00:00 | 80 | 32.1 | +47.9 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-22 22:30:00 | 85 | 37.4 | +47.6 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-20 00:30:00 | 80 | 34.8 | +45.2 | model_limit | Local systematic bias (phase_mismatch); 3 neighbors share residual sign. |
| 2026-07-16 22:00:00 | 85 | 39.9 | +45.1 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-16 23:00:00 | 80 | 35.7 | +44.3 | model_limit | Local systematic bias (phase_mismatch); 3 neighbors share residual sign. |
| 2026-07-16 22:30:00 | 82 | 37.9 | +44.1 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-22 23:00:00 | 80 | 36.1 | +43.9 | model_limit | Local systematic bias (phase_mismatch); 3 neighbors share residual sign. |
| 2026-07-16 21:30:00 | 85 | 41.7 | +43.3 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-20 00:00:00 | 80 | 37.4 | +42.6 | ambiguous | Large error without a confident dirty-data or model signal. |
| 2026-07-18 15:30:00 | 87 | 44.5 | +42.5 | data_suspect | Large awake logging gap before entry (90 min); recall may be inconsistent. |
| 2026-07-18 13:30:00 | 83 | 40.9 | +42.1 | wake_variation | Expected post-sleep or wake-boundary variation (gap was sleep or t<2.5h since wake). |
| 2026-07-16 21:00:00 | 85 | 43.3 | +41.7 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-23 15:00:00 | 94 | 52.5 | +41.5 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-18 13:00:00 | 80 | 39.3 | +40.7 | wake_variation | Expected post-sleep or wake-boundary variation (gap was sleep or t<2.5h since wake). |
| 2026-07-16 20:30:00 | 85 | 44.7 | +40.3 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-19 23:30:00 | 80 | 39.9 | +40.1 | ambiguous | Large error without a confident dirty-data or model signal. |

## Assessment

Of 193 points with |error| ≥ 12, 0 look like **wake-alignment artifacts** (pre-wake logs using wrong night's Fitbit row), 24 are **expected wake/sleep-gap variation**, 15 look **data-suspect** (isolated spikes or post-gap inconsistencies), 134 reflect **model limits** (systematic local bias), and 20 are **ambiguous**. Model-limit errors cluster where M4's fixed circadian phase cannot match your day-specific peak timing; expect this until phase can vary by day or more data is logged. Data-suspect points should be manually reviewed in the log; exclude only if you confirm a mis-tap or inconsistent scale use. Largest miss: 2026-07-20 02:00:00 observed 80 vs M4 26.7 (model_limit) — Local systematic bias (phase_mismatch); 3 neighbors share residual sign.
