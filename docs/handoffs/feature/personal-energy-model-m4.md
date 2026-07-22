# Branch: `feature/personal-energy-model-m4`

## Identity

- Tip: `aa398ca`
- Parent/base: `main` at `43f0872` (includes fold-calendar merge)
- Upstream: none
- Role: feature â€” empirical energy model retrofit (M4 personal fit)
- History: [personal-energy-model-m4.history.md](personal-energy-model-m4.history.md)
- Last verified: `2026-07-22`

## Goal and scope

Abbreviation definitions: [energy_model_glossary.md](../../energy_model_glossary.md).

- Goal: Retro-fit the energy curve to empirical alertness logs; adopt CV-winning M4 model in Python and Android.
- Included: fit harness (M1â€“M6), personal M4 in `energy_logic.py` / `EnergyCalculator.kt`, `INTERPOLATED` log status, agent-guides docs sync.
- Excluded: committing empirical CSV to repo; final parameter lock-in (needs â‰¥21 days of logs).
- Acceptance criteria: LOO-CV report generated; M4 enabled when personal config present; tests pass; handoffs valid.

## Changes since branch creation

- `python/analyze_energy_fit.py`, `energy_models.py`, `test_energy_fit.py` â€” M1â€“M6 CV benchmark
- `python/optimize_energy_model.py` â€” delegates to analyze harness
- `python/energy_logic.py` â€” `personal_m4_energy`, `get_personal_energy`
- `python/personal_energy_fit.json`, `python/sleep_settings.json` â€” M4 params
- `python/analyze_energy_fit.py` â€” `--daily-plots`, `--outlier-report`, `--data-review`, `--validation-report` CLI
- `python/empirical_sleep.py` â€” log-inferred wake/duration/episodes when Fitbit dates missing; `excluded_dates` in debt; log datetime exclusions
- `python/energy_log_exclusions.json` â€” owner-excluded log datetimes (4 points)
- `python/tune_k_s.py` â€” k_s grid-search utility
- `python/data_review.py` â€” gap audit, suspicious walkthrough, peak/trough export
- `docs/energy_model_refinement.md` â€” re-fit playbook (cadence, decision tree, metrics)
- `analysis/daily_comparison/`, `m4_outlier_report.*`, `data_review_report.*`, `model_fit_validation_report.md` â€” analysis outputs
- Android: `EnergyCalculator.personalM4Energy`, `ModelSettings.usePersonalModel`, `EmpiricalEnergyManager.interpolateMissedGaps`, `INTERPOLATED` UI
- `docs/ai_agent_guides/` â€” installed guide-kit index
- `python/clock_widget.py`, Android `ClockRenderer.kt` — energy % label uses today's curve peak (not perfection reference)

## Current status

- Working tree: clean after commit
- Validation: `python python/test_energy_fit.py` â€” 26 passed (2026-07-22)
- **Sleep grouping:** wake-episode is now the harness default (`grouping="wake_episode"`); `--calendar-grouping` for legacy comparison
- Re-fit (2026-07-22 post wake-episode): LOO winner **M3** (19.3); **M4** LOO MAE 21.1 / RÂ² âˆ’0.24; in-sample MAE **15.5** / RÂ² **0.39**
- alignment_artifact outliers: **0** (was 13 under calendar grouping)
- Per-day M4 MAE: Jul 21 **11.0**, Jul 22 **5.6**; Jul 20 still worst at **22.3**
- Production: `personal_energy_fit.json` on **M4** with k_s=0.2 (restored after CV harness write)
- Outliers (|err|â‰¥12): model_limit 62, wake_variation 10, data_suspect 8

## Next actions

1. Continue logging toward Jul 25 day-10 bi-weekly re-fit (â‰¥10 days)
2. Review Jul 20 evening model_limit cluster (not alignment â€” sleep inputs now correct)
3. Owner review diff; exclude `Alertness_Master_Log.csv` from commits
4. Open PR to `main` when metrics stabilize on â‰¥10 days

## Merge and cleanup

- PR base: `main`
- Readiness: in progress (committed on branch; PR pending)
- Proposed PR checks: `python python/test_energy_fit.py`, `python scripts/validate_handoffs.py`, Android `EnergyCalculatorTest`
