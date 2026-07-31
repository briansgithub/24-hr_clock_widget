# Re-fit Energy Model with Extended Alertness Data (Jul 23–26)

The new CSV `Alertness_Master_Log (2).csv` contains 14 calendar days (Jul 16–29) vs the original 7 (Jul 16–22). After data quality filtering, this adds **4 high-quality new days** (Jul 23–26) to the fitting set, nearly doubling the sample from 154 → ~280 fit points.

## User Review Required

> [!IMPORTANT]
> **"Too many" missed threshold recommendation:** I propose excluding any day where **≥50% of wake-period slots are MISSED** (i.e., less than half the awake hours have actual ratings). This filters out Jul 27 (41% logged), Jul 28 (0%), and Jul 29 (0%) while keeping days like Jul 18 (55.6% logged) which still have meaningful contiguous blocks. This threshold strikes a balance — below 50% the data is too sparse for reliable curve fitting.
>
> Does this threshold seem right, or would you prefer something stricter (e.g., ≥10 LOGGED points minimum)?

> [!IMPORTANT]
> **Branch strategy:** You asked for a new branch. I'll create `feature/energy-refit-jul29` from the current `feature/personal-energy-model-m4` tip (`456f56a`), so we inherit all the existing M4 infrastructure.

## Data Quality Assessment

### New days summary

| Date | LOGGED | MISSED | Wake Slots | Logged % | Energy Range | Fitbit Sleep | Decision |
|---|---:|---:|---:|---:|---|---|---|
| Jul 23 | 30 | 1 | 31 | 96.8% | 27–94 | 8.7h ✅ | **Include** |
| Jul 24 | 27 | 0 | 27 | 100% | 25–90 | 10.6h ✅ | **Include** |
| Jul 25 | 35 | 0 | 35 | 100% | 19–78 | 6.7h ✅ | **Include** |
| Jul 26 | 34 | 0 | 34 | 100% | 23–90 | 7.0h ✅ | **Include** |
| Jul 27 | 16 | 23 | 39 | 41.0% | 1–60 | 4.3h ✅ | **Exclude** (59% missed) |
| Jul 28 | 0 | 31 | 31 | 0% | — | 8.3h ✅ | **Exclude** (no data) |
| Jul 29 | 0 | 13 | 13 | 0% | — | 3.4h ✅ | **Exclude** (no data, partial) |

Jul 23–26 are excellent: 3 have 100% coverage, Jul 23 is 96.8% with only 1 MISSED slot. All 4 have Fitbit sleep data available.

### Existing Jul 16–22 data
The new CSV also contains the original Jul 16–22 data with identical LOGGED counts, so we can use this single file as the unified source.

## Proposed Changes

### Branch Setup

1. Create `feature/energy-refit-jul29` from current `feature/personal-energy-model-m4` tip
2. Copy `Alertness_Master_Log (2).csv` → `Alertness_Master_Log.csv` (the standard location the harness reads from)

---

### Data Cleaning & Preparation

#### [MODIFY] [energy_log_exclusions.json](file:///H:/Desktop/widgets/24-hr_clock_widget/python/energy_log_exclusions.json)
- Keep existing 4 exclusions (Jul 17–18 owner-reviewed)
- Run outlier detection on new days and add any clear mis-taps or isolated spikes

#### Date-level exclusions
- Add `excluded_dates` to skip Jul 27, 28, 29 in the fitting frame (the `build_fitting_frame` function already supports `excluded_dates`)
- Jul 18 stays despite 44.4% missed — it has enough contiguous blocks and was in the original fit

---

### Re-fit Pipeline Execution

#### Step 1: Run full CV re-fit
```bash
python python/analyze_energy_fit.py --csv "Alertness_Master_Log.csv"
```
- Generates `analysis/energy_fit_report.json` with M1–M6 LOO-CV on 11 days
- Expect improved LOO metrics since we now have 11 days instead of 7

#### Step 2: Outlier report on new data
```bash
python python/analyze_energy_fit.py --outlier-report
```
- Classify new-day outliers (data_suspect, model_limit, wake_variation)
- Flag any clear mis-taps for exclusion review

#### Step 3: Data review
```bash
python python/analyze_energy_fit.py --data-review
```
- Gap audit, sleep restoration, peak/trough predictions for all 11 days

#### Step 4: Daily comparison plots
```bash
python python/analyze_energy_fit.py --daily-plots
```
- Visual comparison of empirical vs M4 predictions per day

#### Step 5: k_s re-tune
```bash
python python/tune_k_s.py
```
- Re-evaluate k_s on expanded dataset (currently 0.2)

#### Step 6: Validation report
```bash
python python/analyze_energy_fit.py --validation-report
```

---

### Production Parameter Update

#### [MODIFY] [personal_energy_fit.json](file:///H:/Desktop/widgets/24-hr_clock_widget/python/personal_energy_fit.json)
- Update with new M4 fit parameters from the 11-day re-fit
- Update `fitted_at`, `cv_mae` metadata

---

### Documentation Updates

#### [MODIFY] [personal-energy-model-m4.md](file:///H:/Desktop/widgets/24-hr_clock_widget/docs/handoffs/feature/personal-energy-model-m4.md)
- Update metrics section with new LOO-CV results
- Note the data expansion from 7 → 11 days

#### [MODIFY] [personal-energy-model-m4.history.md](file:///H:/Desktop/widgets/24-hr_clock_widget/docs/handoffs/feature/personal-energy-model-m4.history.md)
- Append re-fit milestone with new metrics

#### [MODIFY] [energy_model_refinement.md](file:///H:/Desktop/widgets/24-hr_clock_widget/docs/energy_model_refinement.md)
- Update current baseline section with new metrics

## Open Questions

1. **Threshold for "too many" missed entries:** I recommend ≥50% of wake slots missed → exclude. This drops Jul 27/28/29 but keeps Jul 18 (which has sparse but useful blocks). Want me to use a different threshold?

2. **Jul 22 nap handling:** The new CSV shows a SLEEP_EXCLUDED slot at 20:00 on Jul 22 matching a 30-min Fitbit nap (19:54–20:24). The existing harness handles this, but should we verify nap-aware predictions on the expanded set?

## Verification Plan

### Automated Tests
```bash
python python/test_energy_fit.py -v
python scripts/validate_handoffs.py
```

### Metric Regression Checks
- LOO MAE should decrease or hold steady (was 21.1 on 7 days)
- In-sample MAE should improve with more data
- `alignment_artifact` count should remain at 0 (wake-episode grouping)
- `data_suspect` count should stay low on new days (Jul 23–26 look clean)

### Manual Verification
- Review daily comparison plots for Jul 23–26
- Confirm M4 phase alignment with empirical peaks on new days
