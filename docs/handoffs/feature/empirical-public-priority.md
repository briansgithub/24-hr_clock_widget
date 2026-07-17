# Branch: `feature/empirical-public-priority`

## Identity

- Tip: `2a1c25e` — Add permanent branch handoffs and keep night sun fully visible
- Parent: `feature/dynamic-sun-color`; ahead of local `main`
- Role: second feature PR; current HEAD
- Last verified: 2026-07-17

## Goal and scope

Extend the checkpoint branch with priority loading from public `Alertness_Master_Log.csv`, refined Android grogginess rendering, unified Sync conflict resolution, and night-sun visibility parity. Includes permanent branch handoffs.

## Changes since branch creation

- `00d2edc`: prioritize public empirical CSV data.
- `34de264`: improve grogginess gradient start, remove boundary seam, and restore independent toggle.
- `4602eea`: unified Sync merge, conflict-resolution UI, and separate notifications.
- Current commit: night sun stays opaque gold/yellow (Android + Python); add tracked `docs/handoffs/` and always-applied handoff maintenance rule.

## Current status

- Working tree expected clean after this commit/push.
- Owner reports no known functional gaps.
- No approved PR checks/results are recorded.
- Must follow the dynamic-sun-color checkpoint PR and be refreshed onto updated `origin/main`.

## Next actions

1. Complete the dynamic-sun-color checkpoint PR after `main` `2eaab30` is published.
2. Refresh this branch onto updated `origin/main`.
3. Propose PR checks and ask the owner to approve or revise them.
4. Run and record approved checks, then open the PR.
5. After merge: delete this branch and `feature/empirical-energy-logging`.

## Merge and cleanup

- Readiness: in progress; blocked on predecessor PR, base refresh, and validation.
- PR base: updated `origin/main` after dynamic-sun-color merges.
