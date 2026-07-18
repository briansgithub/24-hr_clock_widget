# History: `chore/remove-preview-reset-button`

Append-only milestones for this branch. Current state and next actions belong in [remove-preview-reset-button.md](remove-preview-reset-button.md).

### 2026-07-18 — Branch created
- Commit/base: `origin/main` at `9dd5780`
- Change: Created `chore/remove-preview-reset-button` in a dedicated worktree to remove the Preview-tab "Reset to defaults" button without touching Display/Energy resets.
- Validation/decision: Scope and acceptance criteria recorded in the active handoff.

### 2026-07-18 — Removed Preview reset control
- Commit/base: this commit on `chore/remove-preview-reset-button`
- Change: `ClockPreviewScreen` no longer accepts or shows `onReset`; Preview call site no longer resets home/lock settings from that screen.
- Validation/decision: Grep confirms Display "Reset to Defaults" and Energy "Reset Model Defaults" remain; handoff validation passed.
