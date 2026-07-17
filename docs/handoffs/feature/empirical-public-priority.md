# Branch: `feature/empirical-public-priority`

## Identity

- Tip: `b3fc1b5` — Note published `origin/main` base in branch handoffs
- Parent: `feature/dynamic-sun-color`; ahead of local `main`
- Role: second feature PR; current HEAD
- History: [empirical-public-priority.history.md](empirical-public-priority.history.md)
- Last verified: 2026-07-17

## Goal and scope

Extend the checkpoint branch with priority loading from public `Alertness_Master_Log.csv`, refined Android grogginess rendering, unified Sync conflict resolution, and night-sun visibility parity. Includes permanent branch handoffs.

## Changes since branch creation

- `00d2edc`: prioritize public empirical CSV data.
- `34de264`: improve grogginess gradient start, remove boundary seam, and restore independent toggle.
- `4602eea`: unified Sync merge, conflict-resolution UI, and separate notifications.
- `2a1c25e`: keep night sun opaque gold/yellow (Android + Python); add tracked handoffs and maintenance rule.
- `b0f41ff`, `b3fc1b5`: synchronize handoff tip/base metadata.
- Uncommitted: add hybrid global + per-branch append-only history and archive protocols.

## Current status

- Working tree is dirty only with the requested handoff/history protocol documentation.
- Owner reports no known functional gaps.
- No approved PR checks/results are recorded.
- Must follow the dynamic-sun-color checkpoint PR and be refreshed onto updated `origin/main`.

## Next actions

1. Complete the dynamic-sun-color checkpoint PR (base `origin/main` at `2eaab30` is published).
2. Refresh this branch onto updated `origin/main`.
3. Propose PR checks and ask the owner to approve or revise them.
4. Run and record approved checks, then open the PR.
5. After merge and pull: append closure milestones; archive each handoff and adjacent history; append global HISTORY; then delete both git branches.

## Merge and cleanup

- Readiness: in progress; blocked on predecessor PR, base refresh, and validation.
- PR base: updated `origin/main` after dynamic-sun-color merges.
- Never delete branch documentation; move each handoff and adjacent history to `docs/handoffs/archive/`, then append global `HISTORY.md`.
