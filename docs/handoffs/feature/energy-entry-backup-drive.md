# Branch: `feature/energy-entry-backup-drive`

## Identity

- Tip: committed — real-time Google Drive sync and prompt time label
- Parent/base: `main` at `da84193`
- Upstream: tracks `origin/main` (no feature remote yet)
- Role: feature
- History: [energy-entry-backup-drive.history.md](energy-entry-backup-drive.history.md)
- Last verified: `2026-07-18`

## Goal and scope

- Goal: Persist each user energy log to the Documents CSV backup, then upload that backup to Google Drive; show the log slot date/time on the notification prompt screen.
- Included: `EmpiricalEnergyManager.saveLogs` / `logEnergy` Drive upload after local sync; worker MISSED seeds skip Drive; `EnergyLogInputScreen` datetime label for the aligned 30-min slot.
- Excluded: Changing SyncWorker / MissedDataCheckWorker schedules; Apps Script URL setup; bedtime-countdown work (separate local branch).
- Acceptance criteria: User save from prompt or history edit writes Documents CSV then POSTs CSV to Drive when URL is set; 30-min MISSED seeds still write local JSON/CSV but do not Drive-upload; prompt shows “Logging for {date} at {time}” for the slot being logged.

## Changes since branch creation

- Committed: `saveLogs(..., uploadDrive)` fire-and-forget Drive upload after local sync; `logEnergy(..., uploadDrive=true)` default; worker passes `false`; `EnergyLogInputScreen` shows aligned slot datetime.

## Current status

- Working tree: clean (committed/rebased).
- Base relationship: rebased onto `main` at `da84193`.
- Validation: compile check pending.
- Risks/blockers: Drive upload is async after save (UI does not wait); requires configured `googleDriveUrl` and `localBackupUri` for full path.
- Stashes: none (committed to branch).

## Next actions

1. Owner smoke-test: log from notification prompt; confirm Documents CSV updates and Drive receives upload.
2. When ready for PR: propose automated/manual checks for owner approval.

## Merge and cleanup

- PR base: `main`
- Readiness: in progress
- Required predecessor: none
- After merge: append closure history, archive this file and its history, update global HISTORY/index, then delete local/remote branch after reachability verification.
