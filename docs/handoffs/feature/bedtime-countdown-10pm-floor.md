# Branch: `feature/bedtime-countdown-10pm-floor`

## Identity

- Tip: bedtime 10pm floor in `BedtimeNotificationManager` (this branch tip)
- Parent/base: `main` at `b0187f4`
- Upstream: none
- Role: feature
- History: [bedtime-countdown-10pm-floor.history.md](bedtime-countdown-10pm-floor.history.md)
- Last verified: `2026-07-17`

## Goal and scope

- Goal: Clamp the Android bedtime countdown target so it never falls earlier than 10:00 PM.
- Included: `calculateBedtimeMillis` floor after the existing 1.5h-before-last-sleep + 5-minute rounding.
- Excluded: Changing the 90-minute advance; Python widget; reminder copy beyond this clamp.
- Acceptance criteria: Pre-22:00 computed targets become 22:00; later targets unchanged; today/tomorrow next-occurrence logic preserved.

## Changes since branch creation

- Clamp: after rounding, if local time is before 22:00, set target to 22:00 before mapping to the next future occurrence.

## Current status

- Working tree: clean after this commit (feature + handoffs).
- Base relationship: branched from `main` @ `b0187f4`; ahead by this feature commit; no upstream.
- Validation: handoff validator passed; Android compile not run this session.
- Risks/blockers: none known for the floor itself. Owner confirmed keep 90-minute advance.
- Stashes: none for this work.

## Next actions

1. When ready for PR: propose automated/manual checks for owner approval.
2. Push and open PR into `main` after checks approved.

## Merge and cleanup

- PR base: `main`
- Readiness: ready after checks
- Required predecessor: none
- After merge: append closure history, archive this file and its history, update global HISTORY/index, then delete local/remote branch after reachability verification.
