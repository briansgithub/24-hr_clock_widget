# Archived: `feature/empirical-log-jul16-cutoff`

## Identity

- Final tip: `af95262` — Prune empirical logs before Jul 16 2026 and limit the 10pm missed alert to today.
- Merge: `b2de326` — Merge branch 'feature/empirical-log-jul16-cutoff'
- Parent/base: `main` at `ead09d4`
- History: [empirical-log-jul16-cutoff.history.md](empirical-log-jul16-cutoff.history.md)
- Disposition: merged into `main`
- Last verified: `2026-07-18`

## Goal and scope

- Keep empirical energy logs from 2026-07-16 onward only.
- 10:00 PM missed-data alert counts today only.
- Drive web app URL autofills with external label.

## What landed

- Cutoff prune on load/save/import/merge/export/history.
- `getMissedDataPointsCount()` uses today (midnight→now).
- Default Google Drive Apps Script URL auto-populated in Empirical history UI.

## Validation recorded

- `python scripts/validate_handoffs.py` (pre-merge).
- Android compile deferred (no JAVA_HOME).

## Cleanup

- Local branch deleted: yes
- Remote branch: none
