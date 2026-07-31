# Data Review Report

Generated: 2026-07-22T18:59:04

## How to review suspicious data

1. **data_suspect** outliers (from m4_outlier_report): check the daily plot red markers. If the point is an isolated spike amid smooth neighbors, consider correcting or excluding it.
2. **alignment_artifact** points: logged before the calendar-date Fitbit wake — not bad data; the pipeline used wrong sleep inputs. Re-fit M4 with wake-episode grouping before switching (current comparison: calendar MAE 15.4 vs wake-episode 15.4).
3. **model_limit** clusters: real ratings where M4's fixed phase is wrong — keep logging; do not delete.

## Unlogged regions (MISSED slots)

- **INTERPOLATE** (high confidence): linear fill between two LOGGED points in the same wake period.
- **MODEL_GUESS** (medium/low): M4 + sleep-dynamics model when no bracketing logs.
- **SLEEP_RESTORE**: active Process-S homeostatic discharge during sleep — no subjective value, but `recovery_fraction` and `projected_wake_energy` describe how wake predictions will shift.
- **POST_WAKE_MODEL**: gap crosses sleep; value comes from restored post-sleep model state.
- **SKIP**: only when no wake context exists.

## Sleep as an active force

Main sleep and naps discharge homeostatic pressure (Process S) via `sleep_dynamics.py`. Upon wake, reduced S pressure and nap inertia feed into `predict_energy_sleep_aware()`. This replaces treating sleep slots as merely ignored.

## Naps

Found **1** Fitbit nap(s). Model adds recovery boost for ~3h after each nap end.
- 2026-07-21 09:13–10:53 (1.1h)

## Dynamic peaks and troughs

Predicted extrema use M4 + Fourier + sleep-dynamics S penalty on a 15-min grid. Naps create local S discharge and short inertia, which can introduce secondary peaks/troughs.

## Gap fill proposals

| DateTime | Action | Value | Confidence | Reason |
|---|---|---:|---|---|
| 2026-07-16 00:00:00 | MODEL_GUESS | 26.5 | low | Model guess after 999 min gap — review manually |
| 2026-07-16 00:30:00 | MODEL_GUESS | 24.7 | low | Model guess after 999 min gap — review manually |
| 2026-07-16 01:00:00 | MODEL_GUESS | 23.1 | low | Model guess after 999 min gap — review manually |
| 2026-07-16 01:30:00 | MODEL_GUESS | 21.7 | low | Model guess after 999 min gap — review manually |
| 2026-07-16 02:00:00 | MODEL_GUESS | 20.2 | low | Model guess after 999 min gap — review manually |
| 2026-07-16 02:30:00 | MODEL_GUESS | 18.6 | low | Model guess after 999 min gap — review manually |
| 2026-07-16 03:00:00 | MODEL_GUESS | 17.0 | low | Model guess after 999 min gap — review manually |
| 2026-07-16 03:30:00 | MODEL_GUESS | 15.3 | low | Model guess after 999 min gap — review manually |
| 2026-07-16 08:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (15% discharged toward minimum) | recovery=15% wake≈26 |
| 2026-07-16 08:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (49% discharged toward minimum) | recovery=49% wake≈26 |
| 2026-07-16 09:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (69% discharged toward minimum) | recovery=69% wake≈26 |
| 2026-07-16 09:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (81% discharged toward minimum) | recovery=81% wake≈26 |
| 2026-07-16 10:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (89% discharged toward minimum) | recovery=88% wake≈26 |
| 2026-07-16 10:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (93% discharged toward minimum) | recovery=93% wake≈26 |
| 2026-07-16 11:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (96% discharged toward minimum) | recovery=96% wake≈26 |
| 2026-07-16 11:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (97% discharged toward minimum) | recovery=97% wake≈26 |
| 2026-07-16 12:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (98% discharged toward minimum) | recovery=98% wake≈26 |
| 2026-07-16 12:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (99% discharged toward minimum) | recovery=99% wake≈26 |
| 2026-07-16 13:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (99% discharged toward minimum) | recovery=99% wake≈26 |
| 2026-07-16 13:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (100% discharged toward minimum) | recovery=100% wake≈26 |
| 2026-07-16 15:30:00 | INTERPOLATE | 47.0 | high | Linear between bracketing LOGGED points |
| 2026-07-16 16:00:00 | INTERPOLATE | 57.0 | high | Linear between bracketing LOGGED points |
| 2026-07-17 02:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (12% discharged toward minimum) | recovery=12% wake≈28 |
| 2026-07-17 02:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (47% discharged toward minimum) | recovery=47% wake≈28 |
| 2026-07-17 03:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (68% discharged toward minimum) | recovery=68% wake≈28 |
| 2026-07-17 03:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (80% discharged toward minimum) | recovery=80% wake≈28 |
| 2026-07-17 04:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (88% discharged toward minimum) | recovery=88% wake≈28 |
| 2026-07-17 04:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (93% discharged toward minimum) | recovery=93% wake≈28 |
| 2026-07-17 05:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (96% discharged toward minimum) | recovery=96% wake≈28 |
| 2026-07-17 05:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (97% discharged toward minimum) | recovery=97% wake≈28 |
| 2026-07-17 06:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (98% discharged toward minimum) | recovery=98% wake≈28 |
| 2026-07-17 06:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (99% discharged toward minimum) | recovery=99% wake≈28 |
| 2026-07-17 07:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (99% discharged toward minimum) | recovery=99% wake≈28 |
| 2026-07-17 07:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (100% discharged toward minimum) | recovery=100% wake≈28 |
| 2026-07-17 08:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (100% discharged toward minimum) | recovery=100% wake≈28 |
| 2026-07-17 08:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (100% discharged toward minimum) | recovery=100% wake≈28 |
| 2026-07-17 09:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (100% discharged toward minimum) | recovery=100% wake≈28 |
| 2026-07-17 09:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (100% discharged toward minimum) | recovery=100% wake≈28 |
| 2026-07-17 10:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (100% discharged toward minimum) | recovery=100% wake≈28 |
| 2026-07-17 10:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (100% discharged toward minimum) | recovery=100% wake≈28 |

## Suspicious points to review

- **2026-07-20 02:00:00** obs=80.0 pred=29.2 (alignment_artifact): Logged before calendar-date Fitbit wake; sleep inputs likely misassigned.
- **2026-07-20 02:30:00** obs=77.0 pred=26.6 (alignment_artifact): Logged before calendar-date Fitbit wake; sleep inputs likely misassigned.
- **2026-07-20 01:30:00** obs=80.0 pred=31.7 (alignment_artifact): Logged before calendar-date Fitbit wake; sleep inputs likely misassigned.
- **2026-07-20 01:00:00** obs=80.0 pred=34.2 (alignment_artifact): Logged before calendar-date Fitbit wake; sleep inputs likely misassigned.
- **2026-07-20 00:30:00** obs=80.0 pred=36.6 (alignment_artifact): Logged before calendar-date Fitbit wake; sleep inputs likely misassigned.
- **2026-07-18 15:30:00** obs=87.0 pred=44.7 (data_suspect): Large awake logging gap before entry (90 min); recall may be inconsistent.
- **2026-07-20 00:00:00** obs=80.0 pred=38.9 (alignment_artifact): Logged before calendar-date Fitbit wake; sleep inputs likely misassigned.
- **2026-07-19 23:30:00** obs=80.0 pred=41.1 (ambiguous): Large error without a confident dirty-data or model signal.
- **2026-07-17 21:00:00** obs=80.0 pred=42.6 (data_suspect): Isolated spike/dip vs neighbors (delta=16).
- **2026-07-16 23:30:00** obs=73.0 pred=35.7 (ambiguous): Large error without a confident dirty-data or model signal.

## Predicted peaks and troughs per wake day

### 2026-07-16
- **peak** at 19:06 (t=5.5h, energy≈40, prominence=15.4)

### 2026-07-17
- **peak** at 17:06 (t=5.5h, energy≈42, prominence=15.7)

### 2026-07-18
- **peak** at 16:40 (t=5.5h, energy≈43, prominence=17.6)

### 2026-07-19
- **trough** at 13:32 (t=0.5h, energy≈29, prominence=1.8)
- **peak** at 18:47 (t=5.8h, energy≈44, prominence=15.7)

### 2026-07-20
- **peak** at 15:46 (t=5.8h, energy≈45, prominence=15.7)

### 2026-07-21
- **peak** at 10:52 (t=4.5h, energy≈47, prominence=15.3)
- **trough** at 11:07 (t=4.8h, energy≈32, prominence=15.3)
- **peak** at 16:07 (t=9.8h, energy≈48, prominence=16.6)

### 2026-07-22
- **peak** at 17:12 (t=5.5h, energy≈48, prominence=15.1)
