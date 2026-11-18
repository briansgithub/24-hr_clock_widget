# Archived branch: `feature/personal-energy-model-m4`

## Identity

- Final tip: `456f56a`
- Parent/base: `main` at `43f0872` (includes fold-calendar merge)
- Upstream: none
- Role: feature â€” empirical energy model retrofit (M4 personal fit)
- History: [personal-energy-model-m4.history.md](personal-energy-model-m4.history.md)
- Closed: `2026-07-31`

## Goal and scope

Abbreviation definitions: [energy_model_glossary.md](../../../energy_model_glossary.md).

- Goal: Retro-fit the energy curve to empirical alertness logs; adopt CV-winning M4 model in Python and Android; align Python display settings with Android Display tab.
- Included: fit harness (M1–M6), personal M4 in `energy_logic.py` / `EnergyCalculator.kt`, `INTERPOLATED` log status, agent-guides docs sync, Python Display UI alignment.
- Excluded: committing empirical CSV to repo; final parameter lock-in (needs ≥21 days of logs).
- Acceptance criteria: LOO-CV report generated; M4 enabled when personal config present; tests pass; handoffs valid.

## Changes since branch creation

- `python/analyze_energy_fit.py`, `energy_models.py`, `test_energy_fit.py` — M1–M6 CV benchmark
- `python/optimize_energy_model.py` — delegates to analyze harness
- `python/energy_logic.py` — `personal_m4_energy`, `get_personal_energy`
- `python/personal_energy_fit.json`, `python/sleep_settings.json` — M4 params
- `python/analyze_energy_fit.py` — `--daily-plots`, `--outlier-report`, `--data-review`, `--validation-report` CLI
- `python/empirical_sleep.py` — log-inferred wake/duration/episodes when Fitbit dates missing; `excluded_dates` in debt; log datetime exclusions
- `python/energy_log_exclusions.json` — owner-excluded log datetimes (4 points)
- `python/tune_k_s.py` — k_s grid-search utility
- `python/data_review.py` — gap audit, suspicious walkthrough, peak/trough export
- `docs/energy_model_refinement.md` — re-fit playbook (cadence, decision tree, metrics)
- `analysis/daily_comparison/`, `m4_outlier_report.*`, `data_review_report.*`, `model_fit_validation_report.md` — analysis outputs
- Android: `EnergyCalculator.personalM4Energy`, `ModelSettings.usePersonalModel`, `EmpiricalEnergyManager.interpolateMissedGaps`, `INTERPOLATED` UI
- `docs/ai_agent_guides/` — installed guide-kit index
- `python/clock_widget.py` — added Bathyphase, Acrophase, Grogginess, Wind-down Wedge UI toggles & rendering; standalone draggable Bedtime Countdown window with 1-second live ticker, default-ON state, transparent key background integration, and global pointer bounding-box hover delay (`hover_delay_var`) & timeout (`timeout_delay_var`) tracking across transparent window regions (Android Display tab parity)

## Final status

- Merged into `main` at `62cb343`; subsequent adaptive refinements merged at `aec4184`.
- Validation: `python -m compileall python`, 27 tests passed, `python scripts/validate_handoffs.py` passed (2026-07-26)
- **Sleep grouping:** wake-episode is now the harness default (`grouping="wake_episode"`); `--calendar-grouping` for legacy comparison
- Re-fit (2026-07-22 post wake-episode): LOO winner **M3** (19.3); **M4** LOO MAE 21.1 / RÂ² âˆ’0.24; in-sample MAE **15.5** / RÂ² **0.39**
- alignment_artifact outliers: **0** (was 13 under calendar grouping)
- Per-day M4 MAE: Jul 21 **11.0**, Jul 22 **5.6**; Jul 20 still worst at **22.3**
- Production: `personal_energy_fit.json` on **M4** with k_s=0.2 (restored after CV harness write)
- Outliers (|err|â‰¥12): model_limit 62, wake_variation 10, data_suspect 8

## Merge and cleanup

- Disposition: merged into `main`; local branch deleted after reachability verification.
- Remaining work: the separate dirty `feature/energy-refit-jul29` worktree and two historical stashes require owner review.
