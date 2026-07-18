# Branch: eature/bedtime-countdown-10pm-floor

## Identity

- Tip: committing wallpaper countdown overlay + 22:00 floor
- Parent/base: main at 0187f4
- Upstream: none
- Role: feature
- History: [bedtime-countdown-10pm-floor.history.md](bedtime-countdown-10pm-floor.history.md)
- Last verified: 2026-07-18

## Goal and scope

- Goal: Bedtime countdown never earlier than 10:00 PM, plus optional wallpaper countdown on home/lock.
- Included: 22:00 floor; Display Sleep toggle (default off); smallTopRight 90% dial/sun/moon; countdown placement/alignment; 10s wallpaper refresh.
- Excluded: 90-minute advance change; Python widget; notification redesign.
- Acceptance criteria: Floor + optional overlay with owner-approved placement.

## Changes since branch creation

- 7763b1 / cdcd88b: 22:00 floor + handoff tip.
- This commit: wallpaper/home-lock countdown overlay, settings toggle, sizing and alignment polish.

## Current status

- Working tree: committing for merge into main.
- Validation: python scripts/validate_handoffs.py passed; Android compile not run (deferred).
- Stashes: unrelated sibling WIP remains in stash list.

## Next actions

1. Merge into main (--no-ff), archive handoff, refresh index.

## Merge and cleanup

- PR base: main
- Readiness: ready (owner requested commit + merge; compile deferred)
- Required predecessor: none
