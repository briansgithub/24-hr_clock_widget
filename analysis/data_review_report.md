# Data Review Report

Generated: 2026-07-29T16:31:45

## How to review suspicious data

1. **data_suspect** outliers (from m4_outlier_report): check the daily plot red markers. If the point is an isolated spike amid smooth neighbors, consider correcting or excluding it.
2. **alignment_artifact** points: logged before the calendar-date Fitbit wake — not bad data; the pipeline used wrong sleep inputs. Re-fit M4 with wake-episode grouping before switching (current comparison: calendar MAE 18.4 vs wake-episode 18.4).
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

Found **2** Fitbit nap(s). Model adds recovery boost for ~3h after each nap end.
- 2026-07-21 09:13–10:53 (1.1h)
- 2026-07-22 19:54–20:24 (0.5h)

## Dynamic peaks and troughs

Predicted extrema use M4 + Fourier + sleep-dynamics S penalty on a 15-min grid. Naps create local S discharge and short inertia, which can introduce secondary peaks/troughs.

## Gap fill proposals

| DateTime | Action | Value | Confidence | Reason |
|---|---|---:|---|---|
| 2026-07-16 00:00:00 | MODEL_GUESS | 15.6 | low | Model guess after 999 min gap — review manually |
| 2026-07-16 00:30:00 | MODEL_GUESS | 13.9 | low | Model guess after 999 min gap — review manually |
| 2026-07-16 01:00:00 | MODEL_GUESS | 12.2 | low | Model guess after 999 min gap — review manually |
| 2026-07-16 01:30:00 | MODEL_GUESS | 10.4 | low | Model guess after 999 min gap — review manually |
| 2026-07-16 02:00:00 | MODEL_GUESS | 8.7 | low | Model guess after 999 min gap — review manually |
| 2026-07-16 02:30:00 | MODEL_GUESS | 7.0 | low | Model guess after 999 min gap — review manually |
| 2026-07-16 03:00:00 | MODEL_GUESS | 5.3 | low | Model guess after 999 min gap — review manually |
| 2026-07-16 03:30:00 | MODEL_GUESS | 3.7 | low | Model guess after 999 min gap — review manually |
| 2026-07-16 08:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (4% discharged toward minimum) | recovery=4% wake≈24 |
| 2026-07-16 08:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (15% discharged toward minimum) | recovery=15% wake≈24 |
| 2026-07-16 09:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (25% discharged toward minimum) | recovery=25% wake≈24 |
| 2026-07-16 09:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (34% discharged toward minimum) | recovery=34% wake≈24 |
| 2026-07-16 10:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (42% discharged toward minimum) | recovery=42% wake≈24 |
| 2026-07-16 10:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (49% discharged toward minimum) | recovery=49% wake≈24 |
| 2026-07-16 11:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (55% discharged toward minimum) | recovery=55% wake≈24 |
| 2026-07-16 11:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (60% discharged toward minimum) | recovery=60% wake≈24 |
| 2026-07-16 12:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (65% discharged toward minimum) | recovery=65% wake≈24 |
| 2026-07-16 12:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (69% discharged toward minimum) | recovery=69% wake≈24 |
| 2026-07-16 13:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (73% discharged toward minimum) | recovery=72% wake≈24 |
| 2026-07-16 13:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (76% discharged toward minimum) | recovery=76% wake≈24 |
| 2026-07-16 15:30:00 | INTERPOLATE | 47.0 | high | Linear between bracketing LOGGED points |
| 2026-07-16 16:00:00 | INTERPOLATE | 57.0 | high | Linear between bracketing LOGGED points |
| 2026-07-17 02:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (3% discharged toward minimum) | recovery=3% wake≈29 |
| 2026-07-17 02:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (15% discharged toward minimum) | recovery=15% wake≈29 |
| 2026-07-17 03:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (25% discharged toward minimum) | recovery=25% wake≈29 |
| 2026-07-17 03:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (34% discharged toward minimum) | recovery=34% wake≈29 |
| 2026-07-17 04:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (41% discharged toward minimum) | recovery=41% wake≈29 |
| 2026-07-17 04:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (48% discharged toward minimum) | recovery=48% wake≈29 |
| 2026-07-17 05:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (54% discharged toward minimum) | recovery=54% wake≈29 |
| 2026-07-17 05:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (60% discharged toward minimum) | recovery=60% wake≈29 |
| 2026-07-17 06:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (64% discharged toward minimum) | recovery=64% wake≈29 |
| 2026-07-17 06:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (69% discharged toward minimum) | recovery=69% wake≈29 |
| 2026-07-17 07:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (72% discharged toward minimum) | recovery=72% wake≈29 |
| 2026-07-17 07:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (76% discharged toward minimum) | recovery=76% wake≈29 |
| 2026-07-17 08:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (78% discharged toward minimum) | recovery=78% wake≈29 |
| 2026-07-17 08:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (81% discharged toward minimum) | recovery=81% wake≈29 |
| 2026-07-17 09:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (83% discharged toward minimum) | recovery=83% wake≈29 |
| 2026-07-17 09:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (85% discharged toward minimum) | recovery=85% wake≈29 |
| 2026-07-17 10:00:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (87% discharged toward minimum) | recovery=87% wake≈29 |
| 2026-07-17 10:30:00 | SLEEP_RESTORE | — | model | Active Process-S restoration during main sleep (88% discharged toward minimum) | recovery=88% wake≈29 |

## Suspicious points to review

- **2026-07-25 23:30:00** obs=78.0 pred=24.9 (ambiguous): Large error without a confident dirty-data or model signal.
- **2026-07-20 00:00:00** obs=80.0 pred=37.4 (ambiguous): Large error without a confident dirty-data or model signal.
- **2026-07-18 15:30:00** obs=87.0 pred=44.5 (data_suspect): Large awake logging gap before entry (90 min); recall may be inconsistent.
- **2026-07-19 23:30:00** obs=80.0 pred=39.9 (ambiguous): Large error without a confident dirty-data or model signal.

## Predicted peaks and troughs per wake day

### 2026-07-16
- **peak** at 18:36 (t=5.0h, energy≈36, prominence=13.6)
- **trough** at 01:51 (t=12.2h, energy≈16, prominence=9.7)
- **peak** at 02:06 (t=12.5h, energy≈25, prominence=9.7)

### 2026-07-17
- **peak** at 16:36 (t=5.0h, energy≈42, prominence=14.3)
- **trough** at 05:06 (t=17.5h, energy≈5, prominence=11.1)

### 2026-07-18
- **peak** at 16:10 (t=5.0h, energy≈38, prominence=17.3)
- **trough** at 04:25 (t=17.2h, energy≈2, prominence=10.3)

### 2026-07-19
- **peak** at 18:02 (t=5.0h, energy≈42, prominence=12.7)
- **trough** at 03:47 (t=14.8h, energy≈13, prominence=10.2)
- **peak** at 04:02 (t=15.0h, energy≈23, prominence=5.0)

### 2026-07-20
- **peak** at 15:01 (t=5.0h, energy≈40, prominence=14.5)
- **trough** at 22:01 (t=12.0h, energy≈20, prominence=8.4)
- **peak** at 22:16 (t=12.2h, energy≈29, prominence=8.4)

### 2026-07-21
- **peak** at 10:52 (t=4.5h, energy≈49, prominence=21.4)
- **trough** at 11:07 (t=4.8h, energy≈14, prominence=14.4)
- **peak** at 15:52 (t=9.5h, energy≈28, prominence=14.4)

### 2026-07-22
- **peak** at 16:27 (t=4.8h, energy≈44, prominence=5.6)
- **trough** at 19:42 (t=8.0h, energy≈38, prominence=5.6)
- **peak** at 20:27 (t=8.8h, energy≈46, prominence=15.3)
- **trough** at 20:42 (t=9.0h, energy≈9, prominence=15.1)
- **peak** at 01:27 (t=13.8h, energy≈24, prominence=4.1)

### 2026-07-23
- **peak** at 16:36 (t=4.8h, energy≈44, prominence=17.4)
- **trough** at 03:51 (t=16.0h, energy≈10, prominence=8.2)
- **peak** at 04:06 (t=16.2h, energy≈18, prominence=2.1)

### 2026-07-24
- **peak** at 19:36 (t=5.0h, energy≈47, prominence=15.2)
- **trough** at 04:06 (t=13.5h, energy≈22, prominence=7.0)
- **peak** at 04:21 (t=13.8h, energy≈29, prominence=7.0)

### 2026-07-25
- **peak** at 15:40 (t=4.8h, energy≈42, prominence=16.4)

### 2026-07-26
- **peak** at 16:57 (t=4.5h, energy≈43, prominence=12.6)
- **trough** at 04:12 (t=15.8h, energy≈10, prominence=12.8)
- **peak** at 04:27 (t=16.0h, energy≈23, prominence=2.6)

### 2026-07-27
- **peak** at 13:41 (t=5.0h, energy≈39, prominence=15.7)
