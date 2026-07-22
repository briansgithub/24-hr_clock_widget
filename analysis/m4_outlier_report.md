# M4 Outlier Deviation Report

Generated: 2026-07-22T18:59:13
Points analyzed: 154 LOGGED over 7 days
Outlier threshold: |error| ≥ 12.0

## Classification summary

- **model_limit**: 62
- **wake_variation**: 10
- **ambiguous**: 9
- **data_suspect**: 8

## Top outliers

| DateTime | Obs | Pred | Error | Class | Rationale |
|---|---:|---:|---:|---|---|
| 2026-07-20 17:00:00 | 25 | 63.1 | -38.1 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-20 02:00:00 | 80 | 45.6 | +34.4 | model_limit | Local systematic bias (phase_mismatch); 3 neighbors share residual sign. |
| 2026-07-20 02:30:00 | 77 | 43.2 | +33.8 | model_limit | Local systematic bias (phase_mismatch); 3 neighbors share residual sign. |
| 2026-07-20 01:30:00 | 80 | 47.7 | +32.3 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-18 13:30:00 | 83 | 51.3 | +31.7 | wake_variation | Expected post-sleep or wake-boundary variation (gap was sleep or t<2.5h since wake). |
| 2026-07-20 17:30:00 | 31 | 62.4 | -31.4 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-20 19:00:00 | 28 | 59.3 | -31.3 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-16 22:00:00 | 85 | 53.7 | +31.3 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-19 14:00:00 | 82 | 51.0 | +31.0 | wake_variation | Expected post-sleep or wake-boundary variation (gap was sleep or t<2.5h since wake). |
| 2026-07-18 13:00:00 | 80 | 49.1 | +30.9 | wake_variation | Expected post-sleep or wake-boundary variation (gap was sleep or t<2.5h since wake). |
| 2026-07-16 21:30:00 | 85 | 54.7 | +30.3 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-20 01:00:00 | 80 | 49.8 | +30.2 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-18 15:30:00 | 87 | 56.8 | +30.2 | data_suspect | Large awake logging gap before entry (90 min); recall may be inconsistent. |
| 2026-07-20 20:00:00 | 27 | 56.7 | -29.7 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-20 18:30:00 | 31 | 60.4 | -29.4 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-16 21:00:00 | 85 | 55.6 | +29.4 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-16 22:30:00 | 82 | 52.6 | +29.4 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-18 14:00:00 | 82 | 53.2 | +28.8 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-16 20:30:00 | 85 | 56.4 | +28.6 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-16 23:00:00 | 80 | 51.4 | +28.6 | model_limit | Local systematic bias (phase_mismatch); 3 neighbors share residual sign. |
| 2026-07-20 18:00:00 | 33 | 61.4 | -28.4 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-19 16:30:00 | 90 | 61.6 | +28.4 | model_limit | Sustained high-energy cluster; M4 under-predicts afternoon peak. |
| 2026-07-20 00:30:00 | 80 | 51.7 | +28.3 | model_limit | Local systematic bias (phase_mismatch); 3 neighbors share residual sign. |
| 2026-07-20 15:30:00 | 37 | 64.3 | -27.3 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |
| 2026-07-20 20:30:00 | 28 | 55.3 | -27.3 | model_limit | Local systematic bias (phase_mismatch); 4 neighbors share residual sign. |

## Assessment

Of 89 points with |error| ≥ 12, 0 look like **wake-alignment artifacts** (pre-wake logs using wrong night's Fitbit row), 10 are **expected wake/sleep-gap variation**, 8 look **data-suspect** (isolated spikes or post-gap inconsistencies), 62 reflect **model limits** (systematic local bias), and 9 are **ambiguous**. Model-limit errors cluster where M4's fixed circadian phase cannot match your day-specific peak timing; expect this until phase can vary by day or more data is logged. Data-suspect points should be manually reviewed in the log; exclude only if you confirm a mis-tap or inconsistent scale use. Largest miss: 2026-07-20 17:00:00 observed 25 vs M4 63.1 (model_limit) — Local systematic bias (phase_mismatch); 4 neighbors share residual sign.
