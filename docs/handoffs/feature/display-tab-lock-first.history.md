# History: `feature/display-tab-lock-first`

Append-only milestones for this branch. Current state and next actions belong in [`display-tab-lock-first.md`](display-tab-lock-first.md).

### 2026-07-18 — Branch created
- Commit/base: `main` at `3b5bb18`
- Change: Created `feature/display-tab-lock-first` to put Lock Screen first in Display tab switcher and default to it on entry.
- Validation/decision: Scope recorded in active handoff; code change applied in `DisplaySettingsScreen`.

### 2026-07-18 — Lock-first Display tabs implemented
- Commit/base: uncommitted on `3b5bb18`
- Change: Swapped TabRow order (Lock then Home); index 0 now drives lock settings/update/reset; default remains 0 so Lock Screen opens first.
- Validation/decision: Pending owner visual check on device.

### 2026-07-18 — Commit and PR
- Commit/base: pending on this branch
- Change: Committing Display Lock-first tab UX + handoffs; opening PR to `main`.
- Validation/decision: Proposed checks — `python scripts/validate_handoffs.py`; manual Display visual check.
