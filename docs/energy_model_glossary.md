# Energy Model Glossary

Definitions for abbreviations and terms used in the energy-model fit harness,
validation reports, and handoffs.

## Fit metrics

| Term | Full name | Meaning |
|---|---|---|
| **MAE** | Mean absolute error | Average \|observed âˆ’ predicted\| across logged points (lower is better). |
| **RMSE** | Root mean square error | Square root of the average squared error; penalizes large misses more than MAE. |
| **RÂ²** | Coefficient of determination | Share of variance explained by the model; can be negative on small samples when the model is worse than the mean. |
| **Bias** | Mean signed error | Average (predicted âˆ’ observed); positive means the model tends to over-predict. |
| **In-sample** | â€” | Metrics on all logged points used to fit parameters (optimistic). |
| **LOO** | Leave-one-out | Cross-validation that holds out one calendar day per fold. |
| **LOO-CV** | Leave-one-out cross-validation | Primary generalization check in `analyze_energy_fit.py` (also written **CV**). |

## Model tiers (M1â€“M8)

Candidate predictors benchmarked in `python/energy_models.py`. Names match `MODEL_REGISTRY` keys.

| ID | Registry name | Description |
|---|---|---|
| **M1** | `M1_baseline` | Fixed two-process curve; no personal parameter tuning. |
| **M2** | `M2_tuned_two_process` | Personally tuned wake/sleep time constants and circadian amplitude. |
| **M3** | `M3_scaled_process_s` | M2 plus scaled **Process S** (homeostatic) pressure term. |
| **M4** | `M4_circadian_daily_baseline` | Legacy nightly baseline plus wake-relative circadian shape. |
| **M5** | `M5_mixed_fourier` | M4 plus intra-day Fourier harmonics (multi-peak afternoons). |
| **M6** | `M6_time_varying_debt` | M2-style core with time-varying sleep-debt weighting. |
| **M7** | `M7_empirical_clock_fourier` | Legacy personal local-clock Fourier curve without sleep-state corrections. |
| **M8** | `M8_adaptive_sleep` | **Production default:** personal local-clock Fourier baseline plus continuous Process S, observed-night debt, and wake/nap inertia. |

**CV winner** â€” model with the lowest LOO MAE in a given re-fit run (not always adopted for production).

## Sleep and alertness dynamics

| Term | Meaning |
|---|---|
| **Process S** | Homeostatic sleep pressure (BorbÃ©ly two-process model): rises during wake, falls during sleep. |
| **Process C** | Circadian alertness rhythm (phase relative to wake). |
| **k_s** (also **k_S**) | Process S penalty scale in `predict_energy_sleep_aware()`; scales how strongly sleep pressure lowers predicted energy. |
| **s_pressure** | Normalized Process S pressure (0â€“1) at a timestamp from sleep/wake history. |
| **Ï„** / **tau** | Time constant (hours) governing wake or sleep dynamics (e.g. `tau_wake`, `tau_sleep`). |
| **Sleep debt** | Rolling weighted shortfall vs personal sleep need over recent nights. |
| **t** | Hours since main wake (mod 24) used as the circadian phase input. |


## UI display

| Term | Meaning |
|---|---|
| **Energy %** | Percent of today's personal energy curve peak (0–100). Uses the same sleep and debt inputs as the live ring; not compared to a perfection reference night. |
| **Acrophase** | Clock time of the circadian energy peak for the current wake day (model-derived). |
| **Perfection reference** | Hypothetical curve at goal sleep with zero debt; used for rested-cap logic, not for the Energy % hand label. |
## Sleep input grouping

| Term | Meaning |
|---|---|
| **Calendar-date grouping** | Assigns one Fitbit sleep row per log **calendar date** (legacy; misaligns pre-wake midnight logs). |
| **Wake-episode grouping** | Assigns sleep inputs from the **most recent main-sleep wake** before each log timestamp (harness default). |

## Empirical log statuses

| Status | Meaning |
|---|---|
| **LOGGED** | User-entered subjective energy rating (ground truth for fit). |
| **SLEEP_EXCLUDED** | Slot skipped because the user was asleep. |
| **INTERPOLATED** | Gap filled by linear interpolation between neighboring LOGGED points. |
| **MISSED** | Unlogged slot; may receive a gap-fill proposal from data review. |

## Outlier classifications (|error| â‰¥ 12)

| Class | Meaning |
|---|---|
| **alignment_artifact** | Log before calendar-date Fitbit wake with wrong sleep inputs (pipeline bug; rare after wake-episode grouping). |
| **wake_variation** | Expected sharp change at a sleep gap or within ~2.5 h after wake. |
| **model_limit** | Systematic local bias (fixed circadian phase, sustained peaks the model under-predicts). |
| **data_suspect** | Isolated spike or dip vs neighbors; review before excluding from fit. |
| **ambiguous** | Could not classify confidently. |

## Gap-fill actions (data review)

| Action | Meaning |
|---|---|
| **INTERPOLATE** | Fill between two LOGGED points with no sleep between (high confidence). |
| **MODEL_GUESS** | Model-only estimate when logs do not bracket the slot. |
| **SLEEP_RESTORE** | Active Process S recovery during sleep (metadata only, not empirical). |
| **POST_WAKE_MODEL** | Model prediction from recovered state after a sleep boundary. |
| **SKIP** | Leave empty. |

## Other

| Term | Meaning |
|---|---|
| **CLI** | Command-line interface (`python python/analyze_energy_fit.py` flags). |
| **Fitbit** | Wearable sleep API source for wake time, duration, efficiency, and naps. |
| **LOO day** | One calendar day held out during leave-one-out cross-validation. |
